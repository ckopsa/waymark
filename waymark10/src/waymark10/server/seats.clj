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
  CHAIR: it holds the key its sessions present (`sitter_key`) and the
  link its firings go out on (`fire_url` and `fire_token`), on the
  seat's own two doors and the schedule's own two. A seat's chair is
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
            [waymark10.declare :refer [defscenario]]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            ;; the walked kind's own doors, read out of its rdef: the
            ;; filter guard asks whether one of them LEAVES the state
            ;; the walk filters by (bead waymark-fp62.12, R-2), and
            ;; the computed wake names every one of them (R-4)
            [waymark10.machine :as machine]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.math RoundingMode)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.time Instant)
           (java.util Base64)))

(set! *warn-on-reflection* true)

;; ── the engine's own hand ───────────────────────────────────────────

(def seats-actor
  "The system actor the sweep and the router write seats as — the
  definitions `deploy` and members `registrar` precedent."
  (t/principal {:id "waymark10-seats" :type :system :display "Seats"}))

(def hook-role
  "The role the close hook's hand wears (routes/seats `sitting-close`).
  The hook acts as the sitter, so its principal is the sitter's — this
  role is the one mark that tells its close from the sitter's own
  `close` through the door, and only the route puts it there."
  :sitting-close-hook)

(def closed-by-paths
  "The four ways a sitting ends closed: through the `close` DOOR, by
  the run's own stop HOOK, by the idle SWEEP (`sweep-sittings!`), or
  born closed as a MISSED fire (`wakes/sweep-missed!`)."
  ["door" "hook" "sweep" "missed"])

(def ^:private closed-by-choices
  {"door" "Door — closed through the sitting's own close action"
   "hook" "Hook — closed by the run's stop hook"
   "sweep" "Sweep — closed by the engine after the sitting went idle"
   "missed" "Missed — a fire nobody sat in, born closed"})

(def outcomes
  "What a closed sitting came to (seat health 1, ticket fad586b7): ONE
  value, the first of these that holds, in this order. `sitting-health`
  is the judge, and nothing else writes the field."
  ["submitted" "stalled" "never_sat" "refused_out" "cut_short" "idle"])

(def ^:private outcome-choices
  {"submitted" "Submitted — it submitted a change"
   "stalled" "Stalled — it stalled a change"
   "never_sat" "Never sat — a missed fire, or a run that took no turn"
   "refused_out" "Refused out — its last write was a refusal"
   "cut_short" "Cut short — the hook closed it after a few turns"
   "idle" "Idle — it walked rows and moved nothing"})

(def health-thresholds
  "The numbers seat health judges a sitting by, in ONE place. The
  `outcome` and `flags` field help spell these same values, and piece 2
  and the Seat Health page read them here rather than restating them."
  {;; a hook close under this many turns is `cut_short`
   :cut-short-turns 10
   ;; more bench.test calls than this is `test_thrash`
   :test-calls 6
   ;; more bench.read plus bench.find bytes than this is `read_heavy`
   :read-bytes 150000
   ;; …and so is more than this share of the bytes served, dropped
   :dropped-share 1/2
   ;; this many earlier unsubmitted sittings on one row is `rewalk`
   :rewalk-sittings 2
   ;; a sitting that cost more dollars than this is `over_budget`
   :cost-usd 3M
   ;; this many refusals or more is `refusals_high`
   :refusals 5
   ;; how far back `backfill-health!` judges the sittings closed
   ;; before the close judged them
   :backfill-days 7
   ;; the closed sittings a seat's `health` is rolled over, for a seat
   ;; that names no `health_window`
   :window 10})

(def health-alert-defaults
  "The `health_alerts` of a seat that states none, and of each rule a
  seat leaves out (seat health 3, ticket 698f6818). `health-breach` is
  the judge."
  {;; a share of submits under this is a breach…
   :submit_rate_below 0.4M
   ;; one sitting in the window that never sat is a breach
   :never_sat_any true
   ;; this many refused_out sittings in a row, newest first
   :refused_out_run 2
   ;; this many cut_short sittings in a row
   :cut_short_run 3
   ;; this many sittings in a row that walked nothing, while rows of the
   ;; kind the seat walks wait outside its grant
   :walked_nothing_run 3
   ;; this many sittings in a row that carry the flag
   :flag_run {:test_thrash 3 :rewalk 2}})

(def ^:private alert-rate-sittings
  "…judged only once the window holds this many sittings: a rate over
  fewer says nothing yet."
  6)

(def ^:private outcome-help
  (str "Written by the engine at the close; the first that holds. submitted: a delivery the seat names in `delivers` (a change submit unless it says otherwise) was made under this sitting. stalled: a change was stalled. never_sat: a missed fire, or no turns and nothing served. refused_out: no transition follows its last refusal. cut_short: closed by the hook in under "
       (:cut-short-turns health-thresholds)
       " turns. idle: none of these; it moved nothing."))

(def ^:private flags-help
  (let [{:keys [test-calls read-bytes dropped-share rewalk-sittings
                cost-usd refusals]} health-thresholds]
    (str "Written by the engine at the close; any number of them. test_thrash: more than "
         test-calls " bench.test calls, or a cancelled run. read_heavy: bench.read plus bench.find over "
         read-bytes " bytes, or more than " dropped-share
         " of the bytes served dropped. rewalk: a row it walked was walked by "
         rewalk-sittings " or more earlier sittings of the seat that did not submit. over_budget: it cost over $"
         cost-usd ". refusals_high: " refusals
         " or more refusals. A refused_out sitting also carries refused:<type> and refused_by:<guard>.")))

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

(defn- halt-mark-of
  "The sit's `halted` block, string-keyed as it answers it, in
  `halt-mark`'s spelling. → a map, or nil."
  [halted]
  (when (seq halted)
    (into {} (keep (fn [[k v]] (when (some? v) [(keyword k) v]))) halted)))

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

(defn chair-of
  "The seat's CHAIR — the first model of its `held_for` (R-2 of
  waymark-fp62.7.23), or nil.

  FIRST, and said here so it is said once: the chair is the model
  whose Routine fires this seat and whose key its sessions present, so
  a step down the ladder is one `restate` of that list and no second
  Routine. A seat held for nothing has no chair — any model may sit in
  it and none of them answers for it. Reads a STORED row as happily as
  a decoded one, `interactive-seat?`'s posture and its reason: a ref
  is a string in the document either way."
  [seat-row]
  (some-> (first (get-in seat-row [:data :held_for])) str not-empty))

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

(g/defguard a-person-at-the-chair
  {:reads [:principal]
   :explain "The chair's key and the chair's link are a person's to write: a person mints the key, a person makes the Routine by hand, and a person — or a tool that person is signed in to — pastes both here. An agent does not write a chair's credential."
   :open "No door changes who the caller is: ask the person to write the chair's key and its link."}
  [_row _inp ctx]
  ;; `a-person`'s three-line check, spelled again rather than reused,
  ;; and schedules.clj's `a-person-or-a-delegate` makes the same trade
  ;; for the same reason: that guard's sentence is about opening an
  ;; OFFICE, and what a caller reads at a model's door is about a
  ;; credential. One admission, two sentences, and the refusal names
  ;; the thing the caller was actually touching.
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
   :explain "The sitter key is written by offer_key alone, never by hand — a create or a restate may not carry sitter_key. Open the seat, or add the model, and offer the key at its own door."}
  [_row inp _ctx]
  (if (contains? inp :sitter_key)
    (t/deny)
    (t/allow)))

;; THE CHAIR'S LINK HAS ONE WRITING DOOR TOO (R-2 of
;; waymark-fp62.7.23). The model kind declares no create-schema of
;; its own, so its row schema IS its create door, and the fence the
;; schedule gets by OMISSION has to be a guard here. `fire_token` is
;; the credential; `fire_url` is judged beside it because half a link
;; written by hand is a link the engine would fire at nothing.
(g/defguard link-not-written-by-hand
  {:judges [:fire_url :fire_token]
   :explain "The Routine's fire URL and its token are written by link alone, never by hand. Add the model first, then link the Routine to it."
   :remedies [:model/link]}
  [_row inp _ctx]
  (if (or (contains? inp :fire_url) (contains? inp :fire_token))
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
  {:reads [:principal :now :grant :seat :held_call :within]
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
          ;; A DELEGATING SEAT'S SITTER IS AN AUTHOR (server/delegation).
          ;; Its seat writes are judged by the delegation guards, which
          ;; stand last on these doors and hold for the person's tap
          ;; what this wall would have refused outright — its own seat
          ;; included. The engine's replay of a held call the person
          ;; allowed passes here for the same reason.
          (or (some? (delegation/author-seat ctx cited))
              (delegation/approved-hold? ctx :seat (:id row)))
          (t/allow)
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


;; ── the walk's own filter (bead waymark-fp62.12, R-1) ───────────────
;;
;; A seat walked its kind under the KIND's default filter and nothing
;; else, so a kind that declares none could not be walked at all —
;; mealplan10's `plan` is one. A scope entry already carries a filter
;; in the grant's own grammar (grants/filter-map-schema): field=value,
;; equality only. That filter is now the walk's, and the seat row
;; gains no field for it. One map therefore says what the seat SEES
;; and what it WALKS, and a row that leaves the filter leaves both in
;; the same commit.

(defn- walk-entries
  "Every scope entry that names this kind, in the order they were
  written."
  [scope walk]
  (when walk
    (filterv #(= walk (str (:kind %))) scope)))

(defn- entry-filter
  "The filter those entries put on the walk, or nil.

  ONE entry, or none: openness absorbs (grants/surface-of), so a
  second entry naming the kind with no filter widens the leash back
  to the whole collection — and a walk narrower than the sight it
  runs under would break the promise this bead is about. Two entries
  therefore walk the kind unfiltered, exactly as the grant reads
  them."
  [entries]
  (when (= 1 (count entries))
    (not-empty (:filter (first entries)))))

(defn walk-filter
  "The filter a seat's walk runs under (R-1), or nil when the walk
  runs under the kind's own default filter as it always has.

  Read from the stored row, because the sit reads it (mcp/walk-of)
  and the wake reads it (`effective-wake-on`). The guards below ask
  the same question of the input at the door."
  [seat-row]
  (let [walk (some-> (get-in seat-row [:data :walk]) str not-empty)]
    (entry-filter (walk-entries (get-in seat-row [:data :scope]) walk))))

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
          entries (walk-entries (:scope inp) walk)
          in-scope? (boolean (seq entries))
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
        ;; A QUEUE FILTERS ITSELF, AND THE FIELD IS THE KIND'S OWN
        ;; (bead waymark-fp62.6.3.10). The check asked for a default
        ;; filter over STATE, which refused `task`: that kind keeps
        ;; its lifecycle in `status`, in the data, because its machine
        ;; is the sync machine and its endings are the authority's
        ;; word (workqueue10.resources.task's own note). What the seat
        ;; needs is that the collection a firing opens is the work
        ;; WAITING and not every row ever mirrored, and a default
        ;; filter over any field says that. So the check asks for one
        ;; default filter, whichever field it names.
        ;; A JUDGMENT'S QUEUE IS THE DEFAULT FILTER (R-4 of
        ;; waymark-fp62.11). A seat that names a judgment walks the
        ;; judgment's `queue` minus the subjects already judged, so
        ;; the collection a firing opens is the work waiting whatever
        ;; the kind declares — the filter stands where the kind's own
        ;; default would have.
        ;; A SCOPE ENTRY'S FILTER IS THE THIRD (bead waymark-fp62.12,
        ;; R-1). The entry that opens the kind may narrow it itself,
        ;; and then the collection a firing opens is that narrowing.
        ;; So one of three must hold, and this clause refuses the
        ;; seat that has none of them; `walk-leaves-its-filter` next
        ;; door asks whether the filter it does have can be emptied.
        (and (empty? (:default-filters rdef))
             (nil? (entry-filter entries))
             (nil? (some-> (:judgment inp) str not-empty)))
        (t/deny {:vars {:walk walk
                        :problem (str "declares no default filter at all,"
                                      " so the collection a firing opens is"
                                      " every row of it rather than the work"
                                      " waiting — give its scope entry a"
                                      " filter, walk a kind whose queue"
                                      " filters itself, or name a judgment"
                                      " whose queue filters it for you")}})
        :else (t/allow)))
    (t/allow)))

;; ── the walk must be able to empty its own filter (R-2) ─────────────
;;
;; A filter the seat cannot get a row out of is a queue that never
;; drains: the firing opens the same rows tomorrow, the sitting bills
;; for them again, and nothing on the row says why. So the engine
;; asks for the PROOF at the door, and it can only ask for the proof
;; it can see.
;;
;; Over `state` it sees everything it needs: the machine is declared,
;; and a door of the walked kind whose `from` includes the filtered
;; state and whose `to` is not that state moves a row out of the
;; filter. The entry must NAME that door — a door the seat may not
;; take is not the seat's way out.
;;
;; Over a data field it sees nothing: a handler writes that field, the
;; declaration does not say which one, and no reading of the rdef can
;; prove a row ever leaves. The one filter it can accept blind is the
;; kind's OWN default, which the kind wrote about itself and which
;; `walk-names-a-kind-in-scope` already accepts as the queue.

(defn- named-filter
  "One filter map as {field-name value-string} — the shape two
  spellings of one field (a keyword off the wire, a string out of the
  store) both reduce to."
  [fm]
  (into {} (map (fn [[k v]] [(name k) (str v)])) fm))

(defn- leaves-the-state?
  "Does one action of `rdef` named in `actions` move a row out of
  `state`? `usability/leaves-state?`'s reading, narrowed to the doors
  this scope entry opens: `:from` is a set and `:to` is one state, so
  a self-loop frees nothing and is not an exit."
  [rdef actions state]
  (let [named (into #{} (map str) actions)]
    (boolean
     (some (fn [a]
             (and (contains? named (name (:name a)))
                  (contains? (into #{} (map name) (:from a)) state)
                  (not= state (some-> (:to a) name))))
           (machine/actions-seq rdef)))))

(g/defguard walk-leaves-its-filter
  ;; :judges [:walk] and :reads [:services], the way
  ;; `walk-names-a-kind-in-scope` does it and for its reason: the
  ;; refusal is ABOUT the walk, and the scope entry beside it is what
  ;; the walk is judged against.
  {:judges [:walk]
   :reads [:services]
   :vars [:walk :problem]
   :open "The walked kind's doors are its published schema's, one GET away; no form can recite another kind's state machine, and the way out of this refusal is the scope entry in this same form."
   :explain "A seat walks a collection its own doors can empty: {problem}."}
  [_row inp ctx]
  (if-some [walk (some-> (:walk inp) str str/trim not-empty)]
    (let [rdef-of (:rdef-of ctx)
          rdef (when rdef-of (rdef-of walk))
          entries (walk-entries (:scope inp) walk)
          entry (when (= 1 (count entries)) (first entries))
          fm (named-filter (entry-filter entries))
          state (get fm "state")
          problem
          (cond
            ;; the probe ctx carries no registry, and a walk naming a
            ;; kind this engine does not serve is the guard next door's
            ;; refusal — decline to guess at both, its own posture
            (or (nil? rdef-of) (nil? rdef)) nil

            ;; R-3: a judgment seat keeps its relaxation. It walks the
            ;; judgment's `queue` minus the subjects already judged,
            ;; and the verdict row is the exit — a door on another
            ;; kind, which nothing here could read off this one.
            (some-> (:judgment inp) str not-empty) nil

            (empty? fm)
            (when (empty? (:default-filters rdef))
              (str "the scope entry for " walk " carries no filter, " walk
                   " declares no default filter, and this seat names no"
                   " judgment — narrow the entry with a filter, or name a"
                   " judgment whose queue narrows it"))

            state
            (when-not (leaves-the-state? rdef (:actions entry) state)
              (str "no door this seat may take moves a " state " " walk
                   " out of " state " — add a door of " walk
                   " that leaves " state " to that scope entry's actions,"
                   " or filter the entry by a state one of its doors"
                   " leaves"))

            ;; A DATA FIELD OVER A KIND THAT NARROWS ITSELF IS THE
            ;; LEASH, NOT THE QUEUE. The bead's sentence is "a filter
            ;; over a field that is not state is accepted only when it
            ;; equals the kind's default filter", and the departure
            ;; recorded here is that a kind which declares ANY default
            ;; filter has already answered the question this guard
            ;; asks: its own default is what opens the collection on
            ;; the work waiting, `walk-names-a-kind-in-scope` accepts
            ;; it for that (R-12.32), and the entry's data filter
            ;; narrows the leash beside it as it always has. The rule
            ;; bites where it was written to bite — a kind with NO
            ;; default filter, where the entry's filter is the whole
            ;; of what narrows the walk and nothing in the
            ;; declaration can prove a row ever leaves it.
            :else
            (when (empty? (:default-filters rdef))
              (str "the scope entry for " walk " filters by "
                   (str/join ", " (map (fn [[f v]] (str f "=" v))
                                       (sort-by key fm)))
                   ", which is the whole of what narrows this walk: " walk
                   " declares no default filter of its own, and the engine"
                   " cannot see the handler that writes a data field — so it"
                   " cannot prove a row ever leaves that filter. Filter by"
                   " state, and name the door that leaves it, or walk a kind"
                   " whose own default filter narrows the queue")))]
      (if problem (t/deny {:vars {:walk walk :problem problem}}) (t/allow)))
    (t/allow)))

;; ── the seat that says a judgment (bead waymark-fp62.11, R-4) ───────

(g/defguard walk-matches-the-judgment
  ;; :judges names :judgment alone, `walk-names-a-kind-in-scope`'s own
  ;; reading: the refusal is ABOUT the judgment this seat cites, and
  ;; the walk and the scope beside it are what it is judged against.
  ;; `:remedies` names the one DOOR that turns a refusal into a pass
  ;; without the person changing a word of what they typed — a draft
  ;; judgment is promoted, and the other two readings name a field of
  ;; the form the person is already standing in.
  {:judges [:judgment]
   :reads [:judgment :principal :now :grant :seat :held_call :within]
   :vars [:problem]
   :remedies [:judgment/promote]
   :explain "A seat that says a judgment walks that judgment's own subjects and answers with its verdicts: {problem}."}
  [row inp ctx]
  (if-some [id (some-> (:judgment inp) str not-empty)]
    ;; the pure render probe carries no read hooks — advertise
    ;; optimistically there, `held-for-active-models`' posture
    (if-some [read' (:read ctx)]
      (let [j (read' :judgment id)
            subject (some-> (get-in j [:data :subject_kind]) str not-empty)
            walk (some-> (:walk inp) str str/trim not-empty)
            says-verdicts? (boolean
                            (some (fn [e]
                                    (and (= "verdict" (str (:kind e)))
                                         (some #(= "judge" (str %))
                                               (:actions e))))
                                  (:scope inp)))
            problem (cond
                      (nil? j)
                      (str "there is no judgment " id " on this engine")

                      ;; AN AUTHORED SEAT IS BORN PARKED (server/delegation,
                      ;; invariant 5), and a parked seat walks nothing. So
                      ;; its author may cite a DRAFT and promote it while
                      ;; the seat still waits on its person's unpark —
                      ;; `judgment-in-force` refuses the unpark until it
                      ;; is promoted.
                      (and (= :draft (:state j))
                           (nil? (:id row))
                           (some? (delegation/authoring-seat ctx :seat nil)))
                      nil

                      ;; a superseded judgment cannot be promoted, so
                      ;; it is `walk-judgment-not-superseded`'s to
                      ;; refuse: this guard's remedy is a promote
                      (= :superseded (:state j))
                      nil

                      (not= :promoted (:state j))
                      (str "that judgment is " (name (:state j))
                           ", and only a promoted one is walked — promote it,"
                           " or name one that is already promoted")

                      (not= walk subject)
                      (str "its subjects are rows of kind " subject
                           ", and this seat walks " (or walk "nothing")
                           " — set the walk to " subject)

                      (not says-verdicts?)
                      (str "the scope opens no way to say one — add an entry"
                           " for kind verdict with the action judge"))]
        (if problem (t/deny {:vars {:problem problem}}) (t/allow)))
      (t/allow))
    (t/allow)))

(g/defguard walk-judgment-not-superseded
  ;; No remedy: a superseded judgment cannot be promoted, so the door
  ;; `walk-matches-the-judgment` names would only send the reader to a
  ;; wall — `judgment-not-superseded`'s split, at the create and the
  ;; restate. Say where the house went next (ticket 86514746).
  {:judges [:judgment]
   :open "No door revives a superseded judgment. The way out is in this same form: name the judgment the sentence names, or another promoted one."
   :reads [:judgment]
   :vars [:problem]
   :explain "A seat that says a judgment walks that judgment's own subjects and answers with its verdicts: {problem}."}
  [_row inp ctx]
  (let [id (some-> (:judgment inp) str not-empty)
        read' (:read ctx)
        j (when (and id read') (read' :judgment id))]
    (if (and j (= :superseded (:state j)))
      (t/deny {:vars {:problem
                      (if-some [s (some-> (get-in j [:data :successor])
                                          str not-empty)]
                        (str "that judgment is superseded by " s
                             ", and only a promoted one is walked — name " s
                             " instead")
                        (str "that judgment is superseded with no successor"
                             ", and only a promoted one is walked — name one"
                             " that is promoted"))}})
      (t/allow))))

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

(def ^:private engine-own-kinds
  "The kinds the wake consumer never matches (`wakes/own-kinds`, which
  this file cannot require): a seat woken by its own sitting or its
  own fire would wake itself forever."
  #{"seat" "sitting" "schedule" "subscription"})

(g/defguard wake-on-names-no-engine-kind
  {:judges [:wake_on]
   :reads [:services]
   :vars [:kind]
   :open "The four kinds are the engine's own writing about a wake, fixed in code; every other kind this surface serves may wake a seat."
   :explain "A seat is never woken by {kind}: the engine's own kinds (seat, sitting, schedule, subscription) are dropped before a wake is matched, so a seat cannot wake itself forever. To wake when a fired sitting ends, wake on transcript seal."}
  [_row inp _ctx]
  (if-some [bad (first (for [e (:wake_on inp)
                             :let [k (str (:kind e))]
                             :when (contains? engine-own-kinds k)]
                         k))]
    (t/deny {:vars {:kind bad}})
    (t/allow)))

(g/defguard wake-on-any-of-needs-in
  {:judges [:wake_on]
   :reads [:services]
   :vars [:kind :field]
   :open "A kind's filterable fields and their ops are its collection grammar, one GET away; a field that declares in reads a comma-separated value as any of."
   :explain "The wake_on filter on {kind} gives {field} a comma-separated value, and {field} does not admit any of (in): the value would match as one literal text and wake this seat for nothing. Name one value, or filter by a field the kind declares filterable with in."}
  [_row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (if-some [bad (first (for [e (:wake_on inp)
                               :let [rdef (rdef-of (:kind e))]
                               :when rdef
                               [f v] (:filter e)
                               :let [fname (name f)]
                               :when (and (str/includes? (str v) ",")
                                          (not= "state" fname)
                                          (not (contains? (get (:filterable rdef) (keyword fname)) :in)))]
                           {:kind (str (:kind e)) :field fname}))]
      (t/deny {:vars bad})
      (t/allow))
    ;; the pure render probe carries no registry — decline to guess
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

;; An `inbox`'s `only` is a kind → actions map, the shape of a
;; subscriber's `only`; spelled as `wake_on` entries it is judged by
;; the same two lookups, in two guards of its own because a guard
;; judges the one field it names.

(defn- inbox-entries
  "The seat's `inbox` as `wake_on`-shaped entries: each kind of its
  `only`, then each of its `cues`, so the two guards below judge a
  cue's kind and actions as they judge the `only`."
  [inbox]
  (concat (for [[k actions] (:only inbox)]
            {:kind (name k) :actions actions})
          (for [c (:cues inbox)]
            {:kind (str (:kind c)) :actions (:actions c)})))

(g/defguard inbox-names-real-kinds
  {:judges [:inbox]
   :reads [:services]
   :vars [:kind]
   :open "The legal kind names are well-known's resources, one GET away; enumerating the registry into this form would duplicate it."
   :explain "An inbox holds a kind this surface serves; there is no kind {kind}."}
  [_row inp ctx]
  (if-some [names-of (:action-names ctx)]
    (if-some [bad (wake-on-unknown-kind names-of (inbox-entries (:inbox inp)))]
      (t/deny {:vars {:kind bad}})
      (t/allow))
    (t/allow)))

(g/defguard inbox-names-real-actions
  {:judges [:inbox]
   :reads [:services]
   :vars [:kind :action :actions]
   :open "Each kind's action vocabulary is well-known's actions list, one GET away; the refusal spells the kind's real actions when an entry misses."
   :explain "There is no action {action} on {kind}; its actions are: {actions}. Name its actions, or an empty list for every one."}
  [_row inp ctx]
  (if-some [names-of (:action-names ctx)]
    (if-some [bad (wake-on-unknown-action names-of (inbox-entries (:inbox inp)))]
      (t/deny {:vars bad})
      (t/allow))
    (t/allow)))

;; A cue's `filter` is matched against the row when the inbox door
;; serves its event (`routes.seats/cue-match`), by equality on `state`
;; or on a data field. A field the kind does not declare filterable is
;; a cue that never matches and never says why, so it is refused here.

(g/defguard inbox-cues-filter-declared-fields
  {:judges [:inbox]
   :reads [:services]
   :vars [:kind :field :fields]
   :open "A kind's filterable fields and their ops are its collection grammar, one GET away; the refusal spells them when a cue misses."
   :explain "A cue on {kind} cannot filter by {field}: a cue's filter names `state`, or a field the kind declares filterable (eq), and a comma-separated value (any of) only on a field it declares filterable with in. The filterable fields of {kind} are: {fields}."}
  [_row inp ctx]
  (if-some [rdef-of (:rdef-of ctx)]
    (if-some [bad (first (for [c (:cues (:inbox inp))
                               :let [rdef (rdef-of (:kind c))]
                               :when rdef
                               [f v] (:filter c)
                               :let [fname (name f)
                                     ops (get (:filterable rdef) (keyword fname))]
                               :when (and (not= "state" fname)
                                          (or (not-any? #(contains? ops %) [:eq :in])
                                              (and (str/includes? (str v) ",")
                                                   (not (contains? ops :in)))))]
                           {:kind (str (:kind c)) :field fname
                            :fields (str/join ", " (sort (map name (keys (:filterable rdef)))))}))]
      (t/deny {:vars bad})
      (t/allow))
    ;; the pure render probe carries no registry — decline to guess
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

(g/defguard author-can-take-it
  {:judges [:author]
   :reads [:seat]
   :vars [:detail]
   :explain "A seat is handed only to a seat that delegates, and only when it fits under that seat's ceiling: {detail}."
   :open "The way out is in this same form: name a seat that delegates and whose ceiling covers this one. A seat's ceiling is the delegates field of its own row, one GET away."}
  [row inp ctx]
  ;; hand_to (invariant 3 and 4 of server/delegation): the person's
  ;; tap writes the author AND the approval, so the seat must already
  ;; be one the author could have authored — R-14.3 whole, the ceiling
  ;; judged by the same `misfit` the create and restate doors read.
  (let [author-id (some-> (:author inp) str not-empty)]
    (cond
      (nil? author-id) (t/allow)          ; the schema refuses the blank
      (= author-id (str (:id row)))
      (t/deny {:vars {:detail "a seat does not author itself"}})
      (delegation/delegating? row)
      (t/deny {:vars {:detail (str "this seat carries a ceiling of its own"
                                   " (delegates), and an authored seat may not")}})
      (nil? (:read ctx)) (t/allow)      ; probe ctx — decline to guess
      :else
      (let [author ((:read ctx) :seat author-id)]
        (cond
          (not (and author (delegation/delegating? author)))
          (t/deny {:vars {:detail (str author-id " is not a seat that"
                                       " delegates: it carries no ceiling")}})
          :else
          (if-some [m (delegation/misfit author (:data row))]
            (t/deny {:vars {:detail m}})
            (t/allow)))))))

(g/defguard the-engines-own-hand
  {:reads [:principal]
   :hide true
   :explain "The sweep and the router write this, never a hand at the wire."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(g/defguard the-engine-or-the-persons-tap
  {:reads [:principal :within]
   :hold true
   :vars [:seat :started_at]
   :explain "Abandoning seat {seat}'s sitting, open since {started_at}, is held for the person's tap: the call is recorded as a held_call, and the person's Allow runs it exactly as written."
   :open "No door clears this one. The call waits as a held_call for the person's tap, and an agent that could abandon a sitting alone could end another seat's work."}
  [row _inp ctx]
  ;; NOT hidden (ticket be2c2c16): a door the grant admits must never
  ;; answer not-found. The sweep and a person pass; an agent's abandon,
  ;; the mayor's of another seat's sitting included, waits for the
  ;; person, and the one agent abandon this admits is the engine's
  ;; replay of the held call that person allowed.
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :sitting :abandon (:id row)) (t/allow)
    :else (t/deny {:vars {:seat (str (get-in row [:data :seat]))
                          :started_at (str (get-in row [:data :started_at]))}})))

(def default-idle-seconds
  "The seat schema's own default for `sitting_idle_seconds`, for a row
  written before that field existed. One hour."
  3600)

(defn- ->instant [v]
  (cond (instance? java.time.Instant v) v
        (inst? v) (.toInstant ^java.util.Date v)
        (some-> v str not-empty) (java.time.Instant/parse (str v))))

(g/defguard still-quiet-for-the-sweep
  {:reads [:within]
   :vars [:last_call_at]
   :explain "The sweep ends only a quiet sitting, and this one made a call at {last_call_at}, inside its seat's idle limit."
   :open "Time clears it: the sweep ends this sitting once it has been quiet for its seat's idle limit, and no door hurries that."}
  [row _inp ctx]
  ;; ticket e3dfe60d: the sweep's pass reads `last_call_at` in one
  ;; transaction and ends the sitting in another, so a sit or a call
  ;; stamping in between was closed under its caller. Judged HERE, the
  ;; reading is the row the ending itself holds for update — the stamp
  ;; writes lock the same row — so the stamp and the end are ordered.
  ;; Only the sweep's own ending carries `:within` {:action :sweep},
  ;; with the idle limit it judged by — every other hand passes, the
  ;; seats actor's own endings at a sit included. A row with no stamp
  ;; is judged by the sweep's other clocks alone.
  (let [{:keys [action idle-seconds]} (:within ctx)
        at (->instant (get-in row [:data :last_call_at]))
        now (->instant (:now ctx))]
    (if (or (not= :sweep action) (nil? idle-seconds) (nil? at) (nil? now))
      (t/allow)
      (if (.isBefore ^java.time.Instant at
                     (.minusSeconds ^java.time.Instant now
                                    (long idle-seconds)))
        (t/allow)
        (t/deny {:vars {:last_call_at (str at)}})))))

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

(g/defguard followed-by-a-supersede
  {:reads [:within]
   :hide true
   :explain "A seat follows a judgment's successor when that judgment is superseded, and by nothing else: POST supersede on the judgment, and every seat that says it follows in the same transaction."}
  [_row _inp ctx]
  ;; `folded-by-a-merge`'s posture (ticket 86514746): concealed at the
  ;; wire, open only inside the judgment's own `supersede` handler
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :judgment kind) (= :supersede action))
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

(g/defguard judgment-not-superseded
  ;; No remedy: a superseded judgment cannot be promoted, so the door
  ;; `judgment-in-force` names would only send the reader to a wall.
  ;; Say where the house went next, as `walk-judgment-not-superseded`
  ;; does (ticket 86514746). A supersede re-points parked seats too,
  ;; so this bites only rows written before it did.
  {:reads [:judgment]
   :vars [:judgment :problem]
   :explain "This seat says the judgment {judgment}, which is superseded, and a seat walks only a judgment in force: {problem}, then unpark."
   :open "No door revives a superseded judgment. The way out is this seat's own restate, naming the judgment the sentence names."}
  [row _inp ctx]
  (let [id (some-> (get-in row [:data :judgment]) str not-empty)
        read' (:read ctx)]
    (if (and id read')
      (let [j (read' :judgment id)]
        (if (and j (= :superseded (:state j)))
          (t/deny {:vars {:judgment (or (some-> (get-in j [:data :name]) str)
                                        id)
                          :problem
                          (if-some [s (some-> (get-in j [:data :successor])
                                              str not-empty)]
                            (str "it is superseded by " s
                                 " — restate the seat to name " s)
                            (str "it is superseded with no successor"
                                 " — restate the seat to name a promoted"
                                 " judgment, or without one"))}})
          (t/allow)))
      (t/allow))))

(g/defguard judgment-in-force
  {:reads [:judgment]
   :vars [:judgment]
   :remedies [:judgment/promote]
   :explain "This seat says the judgment {judgment}, which is not promoted, and a seat walks only a judgment in force. Promote it, or restate the seat without it, then unpark."}
  [row _inp ctx]
  ;; An authored seat may be born citing a DRAFT (invariant 5), and
  ;; the unpark is the moment it would start walking one. A person's
  ;; unpark meets this wall too: it is about the seat, not the hand.
  ;; A superseded judgment is `judgment-not-superseded`'s to refuse:
  ;; this one's remedy is a promote, and that one cannot be promoted.
  (let [id (some-> (get-in row [:data :judgment]) str not-empty)
        read' (:read ctx)]
    (if (and id read')
      (let [j (read' :judgment id)]
        (if (and j (not (#{:promoted :superseded} (:state j))))
          (t/deny {:vars {:judgment (or (some-> (get-in j [:data :name]) str)
                                        id)}})
          (t/allow)))
      (t/allow))))

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
  {:reads [:schedule :model :runner_link]
   :explain "Link the Routine's fire URL and token first — to this seat's schedule, or to the model it is held for — or bring a runner in the schedule's or the model's pool live."}
  [row _inp ctx]
  ;; The schedule is read through the ctx `:find` hook — the write's
  ;; own transaction, `schedules/one-per-seat?`'s spelling exactly. A
  ;; ctx without the hook (the pure render probe) advertises
  ;; optimistically, as every cross-row guard here does.
  ;;
  ;; A SEAT WITH NO LINK OF ITS OWN FIRES THROUGH ITS CHAIR (R-5 of
  ;; waymark-fp62.7.23). One Routine stands for one model, so the
  ;; door asks the schedule first — a seat a person linked keeps its
  ;; own Routine — and then the first model of `held_for`, which is
  ;; the chair.
  ;;
  ;; A POOL IS READ AS THE WAKE READS IT (`schedules/pool-of` — this
  ;; file cannot require that one, which requires it): a fire is
  ;; allowed while one of its runners is live. A schedule the provider
  ;; broke is NOT refused here: a person's fire of it is the probe that
  ;; checks a repair, and the engine's own wakes hold on
  ;; `schedules/held?` instead.
  (if-some [find' (:find ctx)]
    (let [sched (first (find' :schedule {:seat (str (:id row))} {:limit 1}))
          read' (:read ctx)
          chair (when read'
                  (some->> (chair-of row) (read' :model)))
          link-of' #(some-> (get-in % [:data :fire_url]) str not-empty)
          runners-of #(some->> (get-in % [:data :runners])
                               (keep (fn [r] (some-> r str not-empty)))
                               seq)
          pool (or (runners-of sched)
                   (when-not (link-of' sched) (runners-of chair)))]
      (if (or (link-of' sched)
              (link-of' chair)
              (and read'
                   (some #(= "live" (some-> (read' :runner_link %) :state name))
                         pool)))
        (t/allow)
        (t/deny)))
    (t/allow)))

(g/defguard one-model-spelling
  {:judges [:name]
   :reads [:model]
   :open "The registered identifiers are the models collection, one query away; enumerating them into the create form would offer exactly the tokens the guard is about to refuse."
   :explain "A model named {name} is already on record — one row per API identifier. If it was retired, reactivate that row rather than minting a second one: its prices are the history a closed sitting was costed against."}
  [row inp ctx]
  ;; on `restate` the row is the model itself, and its own name is no
  ;; collision; on create there is no row, so nothing is set aside
  (if-some [find' (:find ctx)]
    (if (and (some? (:name inp))
             (seq (remove #(= (str (:id %)) (some-> row :id str))
                          (find' :model {:name (str (:name inp))} {:limit 2}))))
      (t/deny {:vars {:name (str (:name inp))}})
      (t/allow))
    (t/allow)))

;; ── handlers ────────────────────────────────────────────────────────

(def ^:private restatable
  "The fields a `restate` states again. `name` is not among them (one
  spelling per seat) and neither is anything the engine writes."
  [:charter :instructions :mode :scope :substitute_drop :held_for
   :substitute_for
   :standing_ttl_seconds :cadence_seconds :sitting_idle_seconds
   :keep_transcripts :transcript_days :inbox :feed_url
   :budget_usd_per_week
   :sitting_budget_tokens :ignore_sitting_budget :walk :judgment
   :rows_per_firing :wake_on :fire_interval_seconds :max_open_sittings
   :release_grace_seconds :health_window :health_alerts :health_breaker
   :delivers :delegates])

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
      ;; `domain` is not among `restatable` (epic aff24e84, piece 1): a
      ;; restate that leaves it out keeps the stored value, so no
      ;; caller written before the field has to name it
      (contains? inp :domain) (assoc-in [:data :domain] (:domain inp))
      ;; `serves` is restated the same way (epic aff24e84, piece 5)
      (contains? inp :serves) (assoc-in [:data :serves] (:serves inp))
      lift? (update :data dissoc :halt))))

(defhandler close-breaker [row _inp _ctx]
  ;; seat health 3: the stated `max_open_sittings` was never lowered, so
  ;; dropping the mark is the whole of it. The breach stays recorded,
  ;; and only a NEW breach opens the breaker again.
  (update row :data dissoc :breaker_open))

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
  `authored_by` and `owner` from its first moment. A person's seat is
  born as it always was.

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

;; THE CHAIR'S LINK, written and forgotten by its own two doors —
;; the schedule's `write-link` and `clear-link` exactly, less the note
;; a model row does not carry. A second link REPLACES the first, so a
;; person who rotates the Routine's token pastes the new one and
;; nothing else moves.
(defhandler set-chair-link [row inp _ctx]
  (-> row
      (assoc-in [:data :fire_url] (:fire_url inp))
      (assoc-in [:data :fire_token] (:token inp))))

(defhandler clear-chair-link [row _inp _ctx]
  (update row :data dissoc :fire_url :fire_token))

;; the runner pool (waymark ticket d16b71bf): the list is restated
;; whole, so adding a link and taking one off are each one restate.
(defhandler set-runners [row inp _ctx]
  (-> row
      (assoc-in [:data :runners] (vec (:runners inp)))
      (update :data #(if-some [o (:runner_order inp)]
                       (assoc % :runner_order o)
                       (dissoc % :runner_order)))))

;; the boot seed's pool (waymark ticket 4e42b3d4): the engine names the
;; one link it seeded from this row, and only while the row names none —
;; a list a person set is never overwritten, here or in the seed.
(g/defguard the-engine-seeds-the-pool
  {:reads [:principal]
   :hide true
   :explain "A seeded runner list is the engine's write at boot; a person names a pool through Runner links."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow) (t/deny)))

(defhandler seed-runners [row inp _ctx]
  (if (seq (get-in row [:data :runners]))
    row
    (assoc-in row [:data :runners] (vec (:runners inp)))))

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
      (assoc-in [:data :substitute_drop] (:substitute_drop inp))
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

(defhandler restate-model [row inp _ctx]
  (reduce (fn [r f] (if (contains? inp f) (assoc-in r [:data f] (get inp f)) r))
          row
          [:name :display :notes]))

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

(defn- closed-by
  "Which path this close came down, from the hand that closes it: the
  engine's own actor is the sweep, the hook's hand wears `hook-role`,
  and every other hand came through the door."
  [ctx]
  (let [p (:principal ctx)]
    (cond
      (= (:id seats-actor) (:id p)) "sweep"
      (contains? (set (:roles p)) hook-role) "hook"
      :else "door")))

;; ── seat health: the outcome and the flags (ticket fad586b7) ────────
;; Pure over the sitting's document, the transitions made under it and
;; the earlier sittings of its seat. No model reads any of it.

(defn- moved?
  "Do the sitting's transitions hold this action on this kind?"
  [transitions kind action]
  (boolean (some #(and (= kind (some-> (:kind %) name))
                       (= action (some-> (:action %) name)))
                 transitions)))

(defn- change-moved?
  "Do the sitting's transitions hold this action on a change?"
  [transitions action]
  (moved? transitions "change" action))

(def default-delivers
  "What a seat that states no `delivers` delivers: a change's submit."
  [{:kind "change" :action "submit"}])

(defn- delivered?
  "Do the sitting's transitions hold one of the seat's deliveries?
  `delivers` is the seat's field; none stated means `default-delivers`."
  [transitions delivers]
  (boolean (some #(moved? transitions
                          (some-> (:kind %) name)
                          (some-> (:action %) name))
                 (or (seq delivers) default-delivers))))

(defn- refused-last?
  "Was the newest refusal the sitting's last write: no transition under
  it is newer than `last_refusal`. A bench write that was allowed
  leaves no stamp of its own, so the transitions are the only later
  writes the row can show."
  [data transitions]
  (when-some [^java.time.Instant at (->instant (get-in data [:last_refusal :at]))]
    (not-any? (fn [t]
                (when-some [^java.time.Instant moved (->instant (:at t))]
                  (.isAfter moved at)))
              transitions)))

(defn- served-of
  "One count of `served` (`:calls`, `:bytes`, `:dropped`), summed over
  the named tools, or over every tool when none is named."
  [served k tools]
  (reduce + 0 (map #(long (or (get-in served [% k]) 0))
                   (or tools (keys served)))))

(defn- rewalked?
  "Was a row this sitting walked already walked by `:rewalk-sittings`
  or more of `earlier` — the documents of its seat's earlier sittings —
  that were judged and did not submit? A sitting that carries no
  outcome was never judged, and is not counted."
  [data earlier]
  (let [unsubmitted (filter #(when-some [o (some-> (:outcome %) name)]
                               (not= "submitted" o))
                            earlier)
        walks (frequencies (mapcat #(distinct (map str (:walked_rows %)))
                                   unsubmitted))]
    (boolean (some #(<= (long (:rewalk-sittings health-thresholds))
                        (long (get walks (str %) 0)))
                   (:walked_rows data)))))

(defn sitting-health
  "A closed sitting's `outcome` and `flags`, from its document, the
  transitions made under it (`sitting-transitions`' rows) and the
  documents of its seat's earlier sittings. `outcomes` names the order
  the first match is taken in, and `health-thresholds` the numbers.
  `delivers` is the seat's own field: the kind+action pairs that count
  as `submitted`, a change's submit when the seat states none.
  → {:outcome str :flags [str …]}."
  [data transitions earlier & [delivers]]
  (let [{:keys [cut-short-turns test-calls read-bytes dropped-share
                cost-usd refusals]} health-thresholds
        closed (some-> (:closed_by data) name)
        turns (long (or (:turns data) 0))
        served (:served data)
        refusal (:last_refusal data)
        outcome (cond
                  (delivered? transitions delivers) "submitted"
                  (change-moved? transitions "stall") "stalled"
                  (or (= "missed" closed) (true? (:missed data))
                      (and (zero? turns) (empty? served))) "never_sat"
                  (refused-last? data transitions) "refused_out"
                  (and (= "hook" closed) (< turns cut-short-turns)) "cut_short"
                  :else "idle")]
    {:outcome outcome
     :flags (cond-> []
              (or (> (served-of served :calls [:bench__test]) test-calls)
                  (pos? (long (or (:cancelled_runs data) 0))))
              (conj "test_thrash")

              (or (> (served-of served :bytes [:bench__read :bench__find])
                     read-bytes)
                  (> (served-of served :dropped nil)
                     (* dropped-share (served-of served :bytes nil))))
              (conj "read_heavy")

              (rewalked? data earlier) (conj "rewalk")

              (some-> (:cost_usd data) bigdec (> cost-usd))
              (conj "over_budget")

              (>= (long (or (:refusals data) 0)) refusals)
              (conj "refusals_high")

              ;; which law refused it out, beside the outcome
              (= "refused_out" outcome)
              (into (keep (fn [[label v]]
                            (when-some [s (some-> v str not-empty)]
                              (str label ":" s))))
                    [["refused" (:type refusal)]
                     ["refused_by" (:guard refusal)]]))}))

(def ^:private seat-health-page
  "The most of one seat's sittings a close reads for `rewalk`, newest
  first. A row walked again after this many sittings is past the page."
  100)

(defn- earlier-sittings
  "The documents of the sittings in `rows` — one seat's newest page —
  that started before the sitting `id`, whose document is `data`."
  [rows id data]
  (let [^java.time.Instant mine (->instant (:started_at data))]
    (into []
          (comp (remove #(= (str id) (str (:id %))))
                (filter (fn [r]
                          (when-some [^java.time.Instant theirs
                                      (->instant (get-in r [:data :started_at]))]
                            (and mine (.isBefore theirs mine)))))
                (map :data))
          rows)))

(defn- stamp-health
  "The close's last write: `outcome` and `flags`, judged from the row as
  the close leaves it. The transitions are read by the sitting's grant
  from its start to now, and the seat's earlier sittings through the
  write's own transaction. The seat's `delivers` says what counts as
  submitted. A ctx with no such hook judges the row alone."
  [row ctx]
  (let [data (:data row)
        grant (some-> (:grant data) str not-empty)
        under (:transitions-under ctx)
        find-rows (:find ctx)
        read' (:read ctx)
        seat (when read'
               (some->> (:seat data) str not-empty (read' :seat)))
        moved (when (and grant under)
                (under grant (->instant (:started_at data)) nil))
        seat-rows (when find-rows
                    (find-rows :sitting {:seat (str (:seat data))}
                               {:limit seat-health-page :newest-first true}))]
    (update row :data merge
            (sitting-health data moved
                            (earlier-sittings seat-rows (:id row) data)
                            (get-in seat [:data :delivers])))))

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
        (assoc-in [:data :closed_by] (closed-by ctx))
        (assoc-in [:data :prices] prices)
        (assoc-in [:data :cost_usd] (cost-of counts prices))
        ;; seat health 1: judged LAST, from the counts, the hand and
        ;; the cost this close has just written
        (stamp-health ctx))))

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
      ;; a hook tallying through a long wait is a run still there
      (assoc-in [:data :last_call_at] (:now ctx))
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

(def ^:private serves-choices
  "Whom a seat works for (epic aff24e84, piece 5), in the person's own
  terms. Said once and shown at every door that takes the field."
  {"own" "Own — this seat works for its own domain"
   "any" "Any — it is a service: the mayor of the domain that asked for a ticket grooms and ranks it"})

(def ^:private mode-help
  "The seat's OWN reading of R-10.8, and the reason the field is on the
  seat rather than on the principal."
  "Who sits here. A fired seat is the seat's work day: it wakes on its cadence, on a wake or on your fire, and a Routine's run does the work. An interactive seat is its training day: you sit in it yourself, the corrections you make are the record a step down the ladder reads, and nothing fires it — a Routine's run that tries is refused.")

(def keep-transcripts-values
  "What a seat keeps of what its sittings said (docs/spec-transcript.md
  R-3.7). `fired` is the default: a Routine's run is the office's own
  work, and an interactive sitting holds a person's own words, so it
  is kept only when the person says so on the seat."
  ["none" "fired" "all"])

(def ^:private keep-transcripts-choices
  {"none" "Keep no transcript: the sit answers no upload key"
   "fired" "Keep the transcripts of fired runs, not of a person's own sittings"
   "all" "Keep every sitting's transcript, a person's own included"})

(def ^:private keep-transcripts-help
  "Whether this seat's sittings keep what they said. A kept transcript is readable only by a grant that names it, and the sweep deletes its lines after the days below.")

(def ^:private transcript-days-help
  "How long the lines of a sealed transcript are kept. After that the sweep deletes them; the transcript row keeps each file's line count and chain, so the record still says what existed.")

(def keep-transcripts-field
  [:keep_transcripts {:default "fired"
                      :examples ["fired"]
                      :x-display {:label "Keep what its sittings said"
                                  :choices keep-transcripts-choices
                                  :help keep-transcripts-help}}
   (into [:enum] keep-transcripts-values)])

(def transcript-days-field
  [:transcript_days {:default 30
                     :examples [30]
                     :x-display {:label "Days a transcript is kept"
                                 :help transcript-days-help}}
   [:int {:min 1 :max 3650}]])

(def ^:private inbox-help
  "What the engine holds for this seat's sittings to pull, said the way a subscriber's `only` is: a kind, and the actions on it that count, or an empty list for every action. Leave it empty for a seat with no inbox; an interactive seat left empty hears its tickets, changes, held calls, approval requests, seats and sittings. A cue is a standing note: the inbox attaches it to each event the cue matches, so the seat reads what to do beside the event.")

(def ^:private inbox-cue-schema
  "One cue of a seat's inbox (ticket ab77e635): a `wake_on`-shaped
  entry, and the note the inbox door attaches to each event the entry
  matches. An empty `actions` is every action of the kind, as it is in
  `only`."
  [:map
   [:kind {:x-options {:from :kinds}
           :x-display {:label "Kind"
                       :help "The collection whose events carry this note — one kind name this engine serves."}}
    [:string {:min 1 :max 64}]]
   [:actions {:x-options {:from :actions :of :kind :each true}
              :x-display {:label "Actions"
                          :help "Which transitions of that kind carry this note, by name. An empty list is every action of the kind."}}
    [:vector [:string {:min 1 :max 64}]]]
   [:filter {:optional true
             :x-display {:label "Only rows matching"
                         :spelled-by-hand grants/filter-spelled-by-hand
                         :help "Which rows this cue is about: field=value pairs in the shape of that kind's own query, the collection grammar's eq. The row is read as it stands when the inbox serves the event. Omit it and every row of the kind matches."}}
    [:maybe grants/filter-map-schema]]
   [:note {:examples ["An ask from household: groom it."]
           :x-display {:label "Note"
                       :help "What this seat does about such an event, in one line. The inbox attaches it to each event this cue matches."}}
    [:string {:min 1 :max 280}]]])

(def inbox-field
  "THE SEAT'S INBOX. A cloud session cannot run a local receiver, so
  the engine holds the inbox and the sitting pulls it; the sit answers
  the address and a key for it (`issue-inbox-key!`). Absent is no
  inbox for a fired seat, and `default-inbox` for an interactive one
  (`inbox-of`). Each kind and each action is judged by `inbox-names-real-kinds`
  and `inbox-names-real-actions`.

  The field says where its tokens come from the way a `wake_on`
  entry's `kind` does (`:x-options {:from :kinds}`): the kinds are
  the keys of `only`, and the chips beside the box offer them. `only`
  itself is a map-of, whose keys no form can list, so it wears the
  reason it is typed (`:spelled-by-hand`), as the scope's `filter`
  does.

  `cues` are standing notes (`inbox-cue-schema`). They never widen or
  narrow what the door serves: `only` decides that. An inbox that
  states `cues` and no `only` keeps the `only` it would have had with
  no inbox stated (`inbox-of`)."
  [:inbox {:optional true
           :examples [{:only {:seat ["restate" "park"]}}]
           :x-options {:from :kinds}
           :x-display {:label "Its inbox"
                       :help inbox-help}}
   [:maybe [:map
            [:only {:optional true
                    :x-options {:from :kinds}
                    :x-display {:label "Kinds and their actions"
                                :spelled-by-hand "A kind → actions map, the shape of a subscriber's `only`, and a form cannot list its keys, so each pair is typed: a kind name, then the list of its actions that count, or an empty list for every action. The chips beside the box offer every kind name."
                                :help "Which transitions the engine holds for this seat's sittings: each kind by name, and the actions on it that count. An empty list counts every action of that kind."}}
             [:map-of :keyword [:vector [:string {:min 1 :max 64}]]]]
            [:cues {:optional true
                    :x-display {:label "Cues"
                                :help "Standing notes for the events this seat hears. Each cue names a kind, its actions and, when it needs one, a filter on the row; the inbox attaches the cue's note to each event it matches. A cue does not change which events the inbox holds. At most 20."}}
             [:vector {:max 20} inbox-cue-schema]]]]])

(def feed-url-field
  "THE SEAT'S OUTSIDE FEED. An inbox outside the engine streams this
  seat's subscription deliveries, and admits a caller by asking the key
  check door (docs/spec-seat.md § 16). The sit answers a short-lived
  token for it (`issue-feed-token!`), so a session never sends the seat
  key to that address. Absent is no feed. It is not `inbox`, which the
  engine holds itself."
  [:feed_url {:optional true
              :examples ["https://inbox.kopsa.info/feed/mayor"]
              :x-display {:label "External feed"
                          :help "An outside inbox that streams this seat's subscription deliveries; the sit answer hands out a short-lived token for it."}}
   [:maybe [:string {:min 9 :max 500}]]])

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
;; (`wake-on-names-real-kinds`, `wake-on-names-real-actions`), and
;; three fields a leash has no use for join them:
;;
;;   filter     which rows count, in the shape of that kind's query
;;              where clause — `grants/filter-map-schema`, the scope
;;              entry's own filter shape, spelled once and worn twice.
;;              On a transition wake it is judged against the MOVED
;;              ROW (waymark-fp62.7.20); on a count wake it picks the
;;              rows counted. Absent, a transition wake matches every
;;              row and a count wake counts the kind's default filter:
;;              the queue a walk works through.
;;   at_least   the size that wakes the seat counting UP: the count
;;              at or ABOVE which it is worth waking. Absent, the
;;              entry is a transition wake and behaves exactly as it
;;              always did.
;;   at_most    the size that wakes the seat counting DOWN: the count
;;              at or BELOW which it is worth waking (waymark-fp62.13).
;;              Zero is the EMPTY queue, and the empty queue is the
;;              one thing nothing could wake a seat on before: a
;;              planner's work begins when no plan is waiting, and a
;;              cadence was the only thing that could start it.
;;   settle_seconds
;;              the quiet time before the seat wakes
;;              (waymark-fp62.17). Every other entry fires on the
;;              LEADING edge: the first match wakes the seat. An
;;              entry with a settle fires on the TRAILING edge
;;              instead. A match writes the moment the wake is due on
;;              the schedule row, and a later match moves that moment
;;              forward, so the seat wakes after the matches stop. A
;;              family chat is the case: the first reply is the
;;              middle of a conversation, and a seat woken by it
;;              reads a chat that is half answered. A count entry may
;;              settle too, because a count is a LEVEL, and a settled
;;              level is a level that held for that long.
;;
;; An entry names ONE of the two sizes. Both in one entry is not a
;; narrower wake, it is two questions the engine cannot answer with
;; one count, so the SCHEMA refuses it (`wake-entry-one-size?`) and
;; the create and the restate say so in the entry's own place.
;;
;; The rest of a scope entry — ids, fields, hashed, args — is a
;; leash's vocabulary and not a wake's: WHAT a woken session may see
;; is decided by the seat's `scope`, one field up, and a wake entry
;; that repeated it would be a second leash nobody is holding.
(def ^:private wake-entry-fields
  "The wake entry's FIELDS, as the map a client draws a row from. The
  law of the entry is `wake-entry-schema` below, which is this map and
  the one rule a map cannot say."
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
                         :help "Which rows this entry is about: field=value pairs in the shape of that kind's own query, the collection grammar's eq. A count wake counts the rows that match; a transition wake wakes this seat only when the row that moved matches. Omit it and every matching transition wakes the seat, and a count wake counts under the kind's own default filter — the queue a walk works through."}}
    [:maybe grants/filter-map-schema]]
   [:at_least {:optional true
               :examples [20]
               :x-display {:label "Rows waiting before it wakes"
                           :help "The size that wakes this seat. The engine counts the rows matching this entry when one of its actions commits, and fires once the count is at or above this number; the fire names no row, so the session walks the queue. Omit it and every matching transition wakes the seat, one row at a time."}}
    [:int {:min 1}]]
   [:at_most {:optional true
              :examples [0]
              :x-display {:label "Rows left before it wakes"
                          :help "The size that wakes this seat as the queue DRAINS. The engine counts the rows matching this entry when one of its actions commits, and fires once the count is at or below this number; the fire names no row, so the session walks the queue and the charter says what to make. Zero wakes the seat when the last matching row leaves, which is the seat whose work begins on an empty queue. An entry names at_least or at_most, and never both."}}
    [:int {:min 0}]]
   [:settle_seconds {:optional true
                     :examples [900]
                     :x-display
                     {:label "Quiet time before it wakes, in seconds"
                      :help "How long the matches must stop before this entry wakes the seat. A match does not fire the seat; it moves the wake forward by this many seconds, and the seat wakes when nothing has matched for that long. The fire names no row, so the session walks the queue. Use it for a conversation, where the first message is not the whole of it. Omit it and the first match wakes the seat at once."}}
    [:int {:min 1 :max 604800}]]
   ;; the settle's cap (ticket 8f482592): matches that arrive faster
   ;; than the settle must not hold the wake back for ever
   [:max_wait_seconds {:optional true
                       :examples [2700]
                       :x-display
                       {:label "Longest wait after the first match, in seconds"
                        :help "The longest a settling entry holds the wake after the FIRST match it heard. Matches that keep arriving move the wake forward only up to this point, so a busy collection cannot keep the seat from waking. Omit it and the wake waits at most three times the quiet time. It means nothing on an entry with no quiet time."}}
    [:int {:min 1 :max 604800}]]])

(defn- wake-entry-one-size?
  "R-12.24's one rule a `:map` cannot say: an entry names at_least or
  at_most, never both. A non-map answers true, because the map beside
  this one has already refused it and one wrong entry owes a person
  one sentence."
  [e]
  (not (and (map? e) (some? (:at_least e)) (some? (:at_most e)))))

(def wake-entry-schema
  "One `wake_on` entry: the fields above, and the rule that the two
  sizes are alternatives.

  The rule is malli's, not a guard's, so it lands where a person's
  eyes are — the entry's own place in the 422 — and it lands at BOTH
  write doors without either of them repeating it.

  The `:json-schema` property is what keeps the form. A client draws a
  list of maps as ROWS when the items projection carries `properties`
  (waymark-fp62.7.9), and an `:and` projects to `allOf`, which carries
  none: the seat's wake_on would have fallen back to the JSON box the
  rows replaced. So the projection published for this node is the
  MAP's own, computed from `wake-entry-fields` rather than spelled a
  second time. Nothing is hidden by that: the rule refuses a shape
  JSON Schema has no word for, and the field help says it in prose."
  [:and
   {:json-schema (schema/json-schema wake-entry-fields)}
   wake-entry-fields
   [:fn {:error/message "An entry names at_least or at_most, not both."}
    #'wake-entry-one-size?]])

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

(def ^:private instructions-example
  "Read the fire text below and do what it says. Sit in the seat it names with waymark_sit, passing the seat's name and the key you were given. Then walk the rows the sit hands you, one at a time, and take the door the charter chooses for each.")

(def ^:private charter-example
  "Decide whether a message asks something of this house, and say what it asks in one line. A receipt for something already bought asks nothing. A person waiting on an answer asks something, even when they are polite about it.")

(def ^:private max-open-sittings-help
  "The one help sentence `max_open_sittings` carries at the row, the
  create door and the restate, with the budget said beside it."
  "How many sittings of this seat may run at once, each on a row of its own. One keeps the seat as it always was: an open sitting holds every wake. Above one, a wake starts another run while a row of the walk is left that no open sitting holds. The week's fuel is unchanged and counts every sitting, so three sittings at once spend it three times as fast.")

(def ^:private health-alerts-help
  (str "The rules each close judges this seat's health by. A rule that newly breaks files one draft ticket, and a rule left out keeps its default. The defaults: a submit rate under "
       (:submit_rate_below health-alert-defaults)
       " once the window holds " alert-rate-sittings
       " sittings, any sitting that never sat, "
       (:refused_out_run health-alert-defaults) " refused out in a row, "
       (:cut_short_run health-alert-defaults) " cut short in a row, test_thrash on "
       (get-in health-alert-defaults [:flag_run :test_thrash])
       " in a row, rewalk on "
       (get-in health-alert-defaults [:flag_run :rewalk]) " in a row, and "
       (:walked_nothing_run health-alert-defaults)
       " in a row that walked nothing while rows wait outside its grant."))

(def ^:private health-breaker-help
  "Tick it and a new breach caps this seat to one sitting at a time until somebody closes the breaker. It does nothing for a seat that runs one sitting at once, and the number the seat states is never lowered.")

(def health-alerts-schema
  "A seat's alert rules (seat health 3). Every rule is optional: a seat
  states the ones it wants moved, and `health-alert-defaults` answers
  for the rest."
  [:map
   [:submit_rate_below {:optional true
                        :x-display {:label "Share that submitted, under which it is a breach"}}
    [:maybe [:decimal {:min 0}]]]
   [:never_sat_any {:optional true
                    :x-display {:label "A sitting that never sat is a breach"}}
    [:maybe :boolean]]
   [:refused_out_run {:optional true
                      :x-display {:label "Refused out in a row"}}
    [:maybe [:int {:min 1 :max 100}]]]
   [:cut_short_run {:optional true
                    :x-display {:label "Cut short in a row"}}
    [:maybe [:int {:min 1 :max 100}]]]
   [:walked_nothing_run {:optional true
                         :x-display {:label "Walked nothing in a row, rows outside its grant"}}
    [:maybe [:int {:min 1 :max 100}]]]
   ;; the flags are declared one by one, so a form offers a number for
   ;; each and no box that wants JSON (usability's spelled-by-hand)
   [:flag_run {:optional true
               :x-display {:label "In a row, by flag"}}
    [:maybe
     [:map
      [:test_thrash {:optional true
                     :x-display {:label "test_thrash in a row"}}
       [:maybe [:int {:min 1 :max 100}]]]
      [:read_heavy {:optional true
                    :x-display {:label "read_heavy in a row"}}
       [:maybe [:int {:min 1 :max 100}]]]
      [:rewalk {:optional true
                :x-display {:label "rewalk in a row"}}
       [:maybe [:int {:min 1 :max 100}]]]
      [:over_budget {:optional true
                     :x-display {:label "over_budget in a row"}}
       [:maybe [:int {:min 1 :max 100}]]]
      [:refusals_high {:optional true
                       :x-display {:label "refusals_high in a row"}}
       [:maybe [:int {:min 1 :max 100}]]]]]]])

(def fire-keys-schema
  "What the seat keeps of the keys its firings carried (R-12.37): one
  entry for each key the engine minted and no sit has spent yet.

  THE HASH AND NOT THE KEY. The key is on the wire one time, in the
  fire text, and the engine stores nothing a reader could present. The
  sit hashes what it was given and compares; a house whose seat rows
  leaked would leak no key.

  Worn by the row alone. Neither the create door nor the restate
  declares the field, and a closed map refuses an unknown key: that
  omission is the fence, the way the schedule fences its link."
  [:vector
   [:map
    [:hash {:x-display
            {:label "The hash of one key"
             :help "The SHA-256 of the key one fire text carried, base64url. The key itself is on the wire one time and is stored nowhere."}}
     [:string {:min 1 :max 128}]]
    [:expires_at {:x-display
                  {:label "When it stops answering"
                   :help "One sitting_idle_seconds after the fire. A key no session spent stops answering at this moment."}}
     :waymark/instant]
    ;; the instant of the fire itself, so the clock sweep can tell a
    ;; run that never sat from one that is still starting
    ;; (`wakes/sweep-missed!`). Optional: an entry held before the
    ;; field existed carries none, and the sweep leaves it to expire.
    [:fired_at {:optional true
                :x-display
                {:label "When it was fired"
                 :help "The instant of the fire that minted this key. A key still unspent some minutes after it is a run that never sat."}}
     :waymark/instant]
    ;; the walk row the fire text named, when it named one, so the sit
    ;; of a seat that runs several sittings at once hands that row and
    ;; claims it, or says another sitting holds it (`fire-key-row`)
    [:row {:optional true
           :x-display
           {:label "The row it named"
            :help "The id of the walk row this fire's text named. The sit that spends the key hands that row, unless another open sitting of the seat already holds it."}}
     [:string {:min 1 :max 128}]]]])

(def ^:private delivers-help
  "What counts as this seat's delivery, each entry a kind and an action. A sitting that made one of these transitions closes as submitted, so a seat whose work is not a change is not counted cut short or idle. Leave it out and a change's submit is the delivery.")

(def delivers-schema
  "SEAT HEALTH: what `sitting-health` calls `submitted` for this seat.
  Absent means `default-delivers`."
  [:vector {:min 1 :max 16}
   [:map
    [:kind {:examples ["change"]
            :x-display {:label "The kind it moves"}}
     [:string {:min 1 :max 64}]]
    [:action {:examples ["submit"]
              :x-display {:label "The action that delivers"}}
     [:string {:min 1 :max 64}]]]])

(def delegates-schema
  "THE CEILING (server/delegation, invariant 2). A seat that carries one
  is a delegating seat: its sitter may open and tune other seats, and
  every seat it writes must fit under this. The scope is in `scope`'s
  own shape, and a child's entries are fitted under it entry by entry
  and filter by filter. It is not the author's own scope: a mayor may
  give bench.write on repo bench without holding it. The three caps
  bound the rest of the office."
  [:map
   [:scope {:examples [grants/scope-example]
            :x-display
            {:label "What its seats may open"
             :help "The widest scope any seat it authors may carry, entry by entry: a child entry fits when it names the same kind, only actions listed here, only ids listed here when this names ids, and every filter pair this entry carries. It need not be inside the delegating seat's own scope."}}
    grants/scope-schema]
   [:budget_usd_per_week {:examples [5M]
                          :x-display
                          {:label "Most fuel per authored seat, in dollars a week"
                           :help "No seat it authors may be given more than this for seven days."}}
    [:decimal {:min 0 :max 100000}]]
   [:sitting_budget_tokens {:examples [60000]
                            :x-display
                            {:label "Most tokens per sitting of an authored seat"
                             :help "No seat it authors may carry a larger sitting ceiling, and none may ignore the ceiling."}}
    [:int {:min 20000 :max 10000000}]]
   [:held_for {:optional true
               :x-display
               {:label "Models its seats may be held for"
                :help "The model row ids a seat it authors may name in held_for and substitute_for. Leave it empty and any model may be named."}}
    [:maybe [:vector [:string {:min 1 :max 64}]]]]])

(def ^:private delegates-field-help
  "Leave it empty for a seat that authors nothing. Fill it and the seat's sitter may open and tune other seats inside it; each one is born parked and waits on your unpark, and anything past it waits on your tap.")

(def ^:private ignore-budget-help
  "Turn it on and a sitting of this seat has no token ceiling: the harness gets no cap, and the sitting_budget_reached wall never stands. The week's dollar budget still does.")

(def default-domain
  "The name of the domain a seat with no `domain` reads as being in."
  "factory")

(defn domain-name-of
  "The name of the domain this seat is in: its stored domain's, read
  through `read'`, or `default-domain` when the row names none. A
  derived default, and nothing is stored."
  [read' row]
  (if-some [id (some-> (get-in row [:data :domain]) str not-empty)]
    (some-> (read' :domain id) (get-in [:data :name]) str)
    default-domain))

(defn- in-domain-field
  "The seat's :computed `in_domain`: `domain-name-of` over the read the
  render ctx lends."
  [row ctx]
  (domain-name-of (:read ctx) row))

(defn spend-by-domain
  "The dollars this seat's sittings of the last seven days cost, by the
  domain each was worked for (epic aff24e84, piece 5): a map from the
  domain's name to dollars. A sitting is charged to the domain that
  asked for the row it walked, which is the row's `requested_by` when
  it has one and otherwise its `domain`; a row that stores neither
  counts as `default-domain`. A sitting that walked several rows is
  shared evenly among them, and one that walked none is charged to the
  seat's own domain."
  [read' find' row ^java.time.Instant now]
  (let [since (.minusSeconds now (* 7 86400))
        walk (some-> (get-in row [:data :walk]) str not-empty keyword)
        own (or (domain-name-of read' row) default-domain)
        asked-by (fn [id]
                   (let [d (:data (when walk (read' walk id)))]
                     (or (some-> (:requested_by d) str not-empty)
                         (some-> (:domain d) str not-empty)
                         default-domain)))
        recent? (fn [s]
                  (when-some [at (get-in s [:data :started_at])]
                    (not (.isBefore (if (instance? java.time.Instant at)
                                      ^java.time.Instant at
                                      (java.time.Instant/parse (str at)))
                                    since))))]
    (reduce (fn [acc s]
              (let [cost (bigdec (or (get-in s [:data :cost_usd]) 0))
                    rows (distinct (map str (get-in s [:data :walked_rows])))
                    names (if (seq rows) (map asked-by rows) [own])
                    share (with-precision 20 (/ cost (count names)))]
                (reduce #(update %1 (keyword %2) (fnil + 0M) share) acc names)))
            {}
            (filter recent?
                    (find' :sitting {:seat (str (:id row))} {:limit 10000})))))

(defn- spend-by-domain-field
  "The seat's :computed `spend_by_domain_7d`: `spend-by-domain` over the
  read and the find the render ctx lends."
  [row ctx]
  (when-some [find' (:find ctx)]
    (spend-by-domain (:read ctx) find' row
                     (or (:now ctx) (java.time.Instant/now)))))

;; ── a domain's budget (epic aff24e84, piece 2) ──────────────────────

(defn dollars
  "A week's budget as a BigDecimal; none reads as zero."
  ^java.math.BigDecimal [v]
  (if (decimal? v) v (bigdec (or v 0))))

(defn money-text
  "A budget as a sentence spells it: 11, not 11.00."
  [v]
  (.toPlainString (.stripTrailingZeros (dollars v))))

(defn domain-row-of
  "The domain row a seat's `domain` value names, read through the ctx's
  hooks: that row, or the one named `default-domain` when the value is
  empty. nil when the hook is absent or no such row is stored."
  [ctx domain]
  (if-some [id (some-> domain str not-empty)]
    (when-some [read' (:read ctx)] (read' :domain id))
    (when-some [find' (:find ctx)]
      (first (find' :domain {:name default-domain} {:limit 1})))))

(defn domain-seats
  "The ACTIVE seats in this domain: the ones that name it, and for
  `default-domain` the ones that name none. A parked, merged or retired
  seat is not among them."
  [find' domain-row]
  (let [id (str (:id domain-row))
        default? (= default-domain (str (get-in domain-row [:data :name])))]
    (filter (fn [s]
              (if-some [d (some-> (get-in s [:data :domain]) str not-empty)]
                (= d id)
                default?))
            (find' :seat (cond-> {:state "active"}
                           (not default?) (assoc :domain id))
                   {:limit 10000}))))

(defn- budgets-of [seat-rows]
  (transduce (map (fn [s] (dollars (get-in s [:data :budget_usd_per_week]))))
             + 0M seat-rows))

(defn domain-allocated
  "The total of the budgets of this domain's active seats."
  [find' domain-row]
  (budgets-of (domain-seats find' domain-row)))

(g/defguard budget-fits-the-domain
  {:reads [:seat :domain]
   :vars [:domain :total :ceiling :headroom]
   :remedies [:domain/restate]
   :explain "The active seats of {domain} would come to {total} dollars a week together, and the domain's budget is {ceiling}; its headroom today is {headroom}. Ask for no more than the headroom, or restate the domain's budget, which is held for its owner's tap."}
  [row inp ctx]
  ;; seat create and restate. A seat that is not active does not count,
  ;; so its restate is not judged; an engine with no domain row for the
  ;; seat has no ceiling to judge by.
  (let [find' (:find ctx)
        domain (when find'
                 (domain-row-of ctx (if (contains? inp :domain)
                                      (:domain inp)
                                      (get-in row [:data :domain]))))]
    (if (or (nil? domain)
            (and (:id row) (not= "active" (name (:state row)))))
      (t/allow)
      (let [ceiling (dollars (get-in domain [:data :budget_usd_per_week]))
            active (domain-seats find' domain)
            today (budgets-of active)
            others (budgets-of (remove (fn [s] (= (str (:id s)) (str (:id row))))
                                       active))
            total (+ others (dollars (:budget_usd_per_week inp)))]
        ;; a domain already over its budget is not made worse, and a
        ;; restate that does not raise its total is not refused for it
        (if (and (pos? (compare total ceiling))
                 (pos? (compare total today)))
          (t/deny {:vars {:domain (str (get-in domain [:data :name]))
                          :total (money-text total)
                          :ceiling (money-text ceiling)
                          :headroom (money-text (- ceiling today))}})
          (t/allow))))))

(g/defguard moved-by-a-domain
  {:reads [:within]
   :hide true
   :explain "A seat's budget is set here by a domain's move_budget and by nothing else: POST move_budget on the domain, and both seats are written in the same transaction."}
  [_row _inp ctx]
  ;; `folded-by-a-merge`'s :within read: nil at the wire, and inside
  ;; the domain's move it names that write
  (let [{:keys [kind action]} (:within ctx)]
    (if (and (= :domain kind) (= :move_budget action))
      (t/allow)
      (t/deny))))

(defhandler set-seat-budget [row inp _ctx]
  ;; the restate's own rule for the halt line: a new budget lifts a
  ;; `budget_reached` line in the same transaction
  (cond-> (assoc-in row [:data :budget_usd_per_week] (:budget_usd_per_week inp))
    (lifts-the-line? row inp) (update :data dissoc :halt)))

(defn- same-field? [row inp f]
  (or (= (get inp f) (get-in row [:data f]))
      (not (field-moved? row inp f))))

(defn- mayor-moves-only-the-budget?
  "Is this restate the one a domain's mayor makes with no tap: `author`
  is the mayor of the domain the seat is in, and the body states every
  field as the row holds it except the week's budget."
  [row inp ctx author]
  (and (some? (:id row))
       (every? #(same-field? row inp %)
               (remove #{:budget_usd_per_week} restatable))
       (or (not (contains? inp :domain))
           (= (str (:domain inp)) (str (get-in row [:data :domain]))))
       (or (not (contains? inp :serves))
           (= (:serves inp) (get-in row [:data :serves])))
       (= (str (:id author))
          (some-> (domain-row-of ctx (get-in row [:data :domain]))
                  (get-in [:data :mayor])
                  str))))

(delegation/mayors-own-domain! #'mayor-moves-only-the-budget?)

(defresource seat
  {:kind :seat
   :plural "seats"
   :states [:active :parked :merged :retired]
   :initial :active
   :terminal #{:merged :retired}
   :nav :system
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :computed {:in_domain
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
               :fn spend-by-domain-field}}
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
    keep-transcripts-field
    transcript-days-field
    inbox-field
    feed-url-field
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
    [:ignore_sitting_budget {:optional true
                             :x-display
                             {:label "Ignore the token ceiling"
                              :help ignore-budget-help}}
     [:maybe :boolean]]
    [:walk {:optional true
            :not-a-ref "It holds the name of the kind this seat walks, never a row id."
            :x-options {:from :kinds}
            :x-display
            {:label "The queue it walks"
             :help "One kind this seat works through, a row at a time, in the order that kind's own default sort gives. The kind must be in the scope above, and the collection a firing opens IS the work waiting for it: give that kind's scope entry a filter, or walk a kind that filters its own queue, or name a judgment below. When the entry filters by state, that same entry must name a door that moves a row out of that state — no door this seat may take, no queue it can empty. Leave it empty for a seat that walks nothing."}}
     [:maybe [:string {:min 1 :max 64}]]]
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
                :help "The transitions that wake this seat, entry by entry: a kind, and the actions on it that count. An entry that names at_least is a count wake: it wakes the seat when that many rows are waiting, and not one row at a time. An entry that names at_most wakes the seat when that few rows are waiting, which is how a seat is woken by an empty queue. A count covers every row of that kind the filter matches, whatever this seat's grant, and the sitting it wakes still sees only its own scope. An entry that names settle_seconds wakes the seat after the matches stop, and not on the first of them. A seat that walks a queue and names nothing here wakes when a row of that queue is created. Leave it empty for a seat that wakes on its cadence alone."}}
     [:maybe wake-on-schema]]
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
    ;; seat's last `health_window` closed sittings, written again each
    ;; time one of them closes (`roll-health!`). WRITTEN BY NO DOOR,
    ;; `fire_keys`' own way: neither the create door nor the restate
    ;; declares `health`, so a body that carries it is refused as an
    ;; unknown key. The window is the person's, and both doors take it.
    [:health_window {:default (:window health-thresholds)
                     :examples [(:window health-thresholds)]
                     :x-display
                     {:label "Sittings its health is read over"
                      :help "The seat's health is counted over this many of its last closed sittings. A smaller window answers sooner to a change; a larger one is steadier."}}
     [:int {:min 1 :max 100}]]
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
    ;; SEAT HEALTH 3 (ticket 698f6818). The rules a close judges the
    ;; rollup by (`health-breach`), and whether a new breach caps the
    ;; seat to one sitting at a time (`breaker_open`, below). Both are
    ;; the person's, and both doors take them.
    [:health_alerts {:optional true
                     :x-display
                     {:label "When its health is a breach"
                      :help health-alerts-help}}
     [:maybe health-alerts-schema]]
    [:health_breaker {:optional true
                      :x-display
                      {:label "Trip a breaker on a breach"
                       :help health-breaker-help}}
     [:maybe :boolean]]
    [:delegates {:optional true
                 :x-display
                 {:label "What it may author"
                  :help delegates-field-help}}
     [:maybe delegates-schema]]
    [:delivers {:optional true
                :x-display
                {:label "What counts as its delivery"
                 :help delivers-help}}
     [:maybe delivers-schema]]
    ;; ── engine-written from here down (absent from the create door
    ;;    and from restate; see the ns docstring's write fence) ───────
    ;; WHO AUTHORED THIS SEAT, AND FOR WHOM (server/delegation,
    ;; invariant 3). Stamped by `seat-born` when a delegating seat's
    ;; sitter opens it, and absent from both input doors, so a closed
    ;; map refuses a hand that tries to write it.
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
              :spelled-by-hand "The person the authoring seat acts for, written at birth; never typed."}}
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
    [:sitter_key {:optional true :secret true
                  :x-display
                  {:hidden true
                   :label "Sitter key"
                   :spelled-by-hand "Written by offer_key and cleared by revoke_key; never typed into a form, and never rendered back."}}
     [:maybe [:string {:min 22 :max 128}]]]
    ;; ── THE KEY OF ONE FIRING (R-12.37) ─────────────────────────────
    ;; The key above is standing, and a person pastes it into a
    ;; Routine by hand. A Routine made without that step fires a
    ;; session that has the seat's name and nothing to sit with, which
    ;; is what the meal planner's first firing showed. These keys are
    ;; the other half: the engine mints 128 bits for EACH fire of a
    ;; seat that has instructions, the fire text carries the key on one
    ;; line, and the row keeps the hash alone. One key opens one sit.
    ;; The Routine's prompt then holds no secret at all.
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
     [:maybe fire-keys-schema]]
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
    keep-transcripts-field
    transcript-days-field
    inbox-field
    feed-url-field
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
    [:ignore_sitting_budget {:default false
                             :x-display
                             {:label "Ignore the token ceiling"
                              :help ignore-budget-help}}
     :boolean]
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
    [:wake_on {:optional true
               :examples [wake-on-example]
               :x-display
               {:label "What wakes it"
                :help "The transitions that wake this seat, entry by entry: a kind, and the actions on it that count. An entry that names at_least is a count wake: it wakes the seat when that many rows are waiting. An entry that names at_most wakes the seat when that few rows are waiting, which is how a seat is woken by an empty queue. A count covers every row of that kind the filter matches, whatever this seat's grant, and the sitting it wakes still sees only its own scope. Leave it empty and the seat wakes on its cadence; a seat that walks a queue wakes when a row of that queue is created."}}
     [:maybe wake-on-schema]]
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
    [:health_window {:default (:window health-thresholds)
                     :examples [(:window health-thresholds)]
                     :x-display
                     {:label "Sittings its health is read over"
                      :help "The seat's health is counted over this many of its last closed sittings. Ten is the default."}}
     [:int {:min 1 :max 100}]]
    [:delivers {:optional true
                :x-display
                {:label "What counts as its delivery"
                 :help delivers-help}}
     [:maybe delivers-schema]]
    [:health_alerts {:optional true
                     :x-display
                     {:label "When its health is a breach"
                      :help health-alerts-help}}
     [:maybe health-alerts-schema]]
    [:health_breaker {:optional true
                      :x-display
                      {:label "Trip a breaker on a breach"
                       :help health-breaker-help}}
     [:maybe :boolean]]
    [:delegates {:optional true
                 :x-display
                 {:label "What it may author"
                  :help delegates-field-help}}
     [:maybe delegates-schema]]
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
   ;; `fire_keys` is NOT declared here (R-12.37): the create door is
   ;; a closed map, and a body that carries the field is refused as
   ;; an unknown key. That omission is the fence.
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
                   drop-inside-scope
                   ttl-within-standing
                   held-for-active-models
                   walk-names-a-kind-in-scope
                   walk-leaves-its-filter
                   walk-judgment-not-superseded
                   walk-matches-the-judgment
                   wake-on-names-real-kinds
                   wake-on-names-real-actions
                   wake-on-names-no-engine-kind
                   wake-on-any-of-needs-in
                   inbox-names-real-kinds
                   inbox-names-real-actions
                   inbox-cues-filter-declared-fields
                   budget-fits-the-domain
                   ;; LAST, so a hold is a call every other wall passed
                   delegation/authors-within-the-ceiling]
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
             keep-transcripts-field
             transcript-days-field
             inbox-field
             feed-url-field
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
             [:ignore_sitting_budget {:default false
                                      :x-display
                                      {:label "Ignore the token ceiling"
                                       :help ignore-budget-help}}
              :boolean]
             [:walk {:optional true
                     :not-a-ref "It holds the name of the kind this seat walks, never a row id."
                     :x-options {:from :kinds}
                     :x-display
                     {:label "The queue it walks"
                      :help "One kind this seat works through, in the scope above, filtering its own queue by state — or the subject kind of the judgment below."}}
              [:maybe [:string {:min 1 :max 64}]]]
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
             [:health_window {:default (:window health-thresholds)
                              :examples [(:window health-thresholds)]
                              :x-display
                              {:label "Sittings its health is read over"
                               :help "The seat's health is counted over this many of its last closed sittings. The next close counts it over the new window."}}
              [:int {:min 1 :max 100}]]
             [:delivers {:optional true
                         :x-display
                         {:label "What counts as its delivery"
                          :help delivers-help}}
              [:maybe delivers-schema]]
             [:health_alerts {:optional true
                              :x-display
                              {:label "When its health is a breach"
                               :help health-alerts-help}}
              [:maybe health-alerts-schema]]
             [:health_breaker {:optional true
                               :x-display
                               {:label "Trip a breaker on a breach"
                                :help health-breaker-help}}
              [:maybe :boolean]]
             [:delegates {:optional true
                          :x-display
                          {:label "What it may author"
                           :help delegates-field-help}}
              [:maybe delegates-schema]]
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
     ;; `fire_keys` is not declared here either (R-12.37): the
     ;; closed map refuses it, and `restate-seat` writes only the
     ;; restatable fields, so the keys a firing holds survive a
     ;; restate the way `sitter_key` does
     :record true
     ;; sitter_key is NOT prefilled and cannot be: the draft view
     ;; serves prefill from the raw row, and resource/check-secret!
     ;; refuses a :secret field there at the declaration.
     :edit {:prefill [:charter :instructions :mode :scope :substitute_drop
                      :held_for
                      :substitute_for :standing_ttl_seconds :cadence_seconds
                      :sitting_idle_seconds
                      :keep_transcripts :transcript_days :inbox :feed_url
                      :budget_usd_per_week :sitting_budget_tokens
                      :ignore_sitting_budget :walk
                      :judgment :rows_per_firing :wake_on
                      :fire_interval_seconds :max_open_sittings
                      :release_grace_seconds :health_window
                      :health_alerts :health_breaker :delivers :delegates
                      :domain :serves]
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
              walk-leaves-its-filter
              walk-judgment-not-superseded
              walk-matches-the-judgment
              wake-on-names-real-kinds
              wake-on-names-real-actions
              wake-on-names-no-engine-kind
              wake-on-any-of-needs-in
              inbox-names-real-kinds
              inbox-names-real-actions
              inbox-cues-filter-declared-fields
              step-carries-a-note
              budget-fits-the-domain
              delegation/authors-within-the-ceiling]
     :safety {:idempotent true :reversible true :confirm false}
     :handler restate-seat
     :display {:label "Restate" :style :primary :order 1
               :description "State the office again, whole — every live grant moves with it at the next request"}}

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
     :guards [a-person not-a-sitter merge-target-is-active
              delegation/the-persons-lever]
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
     :guards [a-person delegation/the-persons-lever]
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
    ;; seat's history says it moved and why. A v10 action declares ONE
    ;; `:to`, so a parked seat has its own door.
    :follow_successor
    {:from #{:active} :to :active
     :input follow-successor-input
     :record true
     :waives #{:edit-shape}
     :guards [followed-by-a-supersede]
     :safety {:idempotent true :reversible false :confirm false}
     :handler follow-successor
     :display {:label "Follow the successor" :order 16}}

    :follow_successor_parked
    {:from #{:parked} :to :parked
     :input follow-successor-input
     :record true
     :waives #{:edit-shape}
     :guards [followed-by-a-supersede]
     :safety {:idempotent true :reversible false :confirm false}
     :handler follow-successor
     :display {:label "Follow the successor" :order 17}}}
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
     [:maybe [:string {:max 240}]]]
    ;; ── THE CHAIR (waymark-fp62.7.23) ───────────────────────────────
    ;; ONE ROUTINE FOR EACH MODEL, so the credentials that Routine
    ;; needs live on the model. Both fields are the seat's and the
    ;; schedule's own, worn here without a change of meaning: a
    ;; session presenting `sitter_key` is a sitter of any seat this
    ;; model is the chair of (`waymark_sit`, R-4), and a seat with no
    ;; link of its own fires through `fire_url` and `fire_token`
    ;; (R-5). A step down the ladder is then one restate of the
    ;; seat's `held_for` — the seat moves to another chair, and
    ;; nobody makes a second Routine.
    [:sitter_key {:optional true :secret true
                  :x-display
                  {:hidden true
                   :label "Chair key"
                   :spelled-by-hand "Written by offer_key and cleared by revoke_key; never typed into a form, and never rendered back."}}
     [:maybe [:string {:min 22 :max 128}]]]
    ;; HIDDEN, and the seat's `schedule` field is the precedent and the
    ;; reason: this kind declares no create-schema, so its row schema
    ;; is its create door as well, and a form offering the fire URL
    ;; would be a form inviting a person to write by hand the one
    ;; thing `link` exists to write. The value is on the row for
    ;; anything that reads it; the door that puts it there is Link.
    [:fire_url {:optional true
                :x-display
                {:hidden true
                 :label "The Routine's fire URL"
                 :spelled-by-hand "Written by Link and cleared by Unlink: the endpoint of the Routine a person made for this model. Every seat it is the chair of, and that nobody linked a Routine of its own, fires through this."}}
     [:maybe [:string {:min 1 :max 400}]]]
    [:fire_token {:optional true :secret true
                  :x-display
                  {:hidden true
                   :label "The Routine's token"
                   :spelled-by-hand "Written by Link and cleared by Unlink; never shown again, and never asked for by a form that already holds it."}}
     [:maybe [:string {:min 16 :max 400}]]]
    ;; the runner pool (waymark ticket d16b71bf): the links every seat
    ;; this model is the chair of fires through, unless its schedule
    ;; names its own list or its own link.
    [:runners {:optional true
               :x-display
               {:label "Runner links"
                :help "The runner links this model's seats fire through, in order. A fire skips a link that is waiting and takes the rest as the pool order says."}}
     [:maybe [:vector {:max 20} [:string {:min 1 :max 200}]]]]
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
   ;; the two write fences of R-2, and the seat's own note about why a
   ;; fence must have something to name: a guard judges a field of the
   ;; door it stands on. This kind declares no create-schema, so its
   ;; row schema IS that door and both fields are already there to be
   ;; refused — which is the whole of what the seat had to declare
   ;; them for.
   :create-guards [one-model-spelling
                   key-not-written-by-hand
                   link-not-written-by-hand]
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

    :link
    {:from #{:active} :to :active
     :input [:map
             [:fire_url {:x-display
                         {:raw true
                          :label "The Routine's fire URL"
                          :help "The endpoint that starts a run, copied from the Routine's own page. It carries the Routine's id, which is not a secret."}}
              [:string {:min 1 :max 400}]]
             [:token {:x-display
                      {:raw true
                       :label "The Routine's token"
                       :help "The credential that opens that one Routine. The engine holds it and never shows it again. Paste it once; a later link replaces it."}}
              [:string {:min 16 :max 400}]]]
     :guards [a-person-at-the-chair]
     :edit {:prefill [:fire_url] :fence false
            :unfenced-reason
            "The token comes from the Routine's own page, not from this row; a link replaces what stands rather than editing it."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The link replaces whatever this model held; Unlink takes it off again."}
     :handler set-chair-link
     :display {:label "Link the Routine" :style :primary :order 6
               :description "Paste the fire URL and the token of the Routine you made for this model — its seats fire through it from then on"}}

    :unlink
    {:from #{:active} :to :active
     :guards [a-person-at-the-chair]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The fire URL and the token leave this model; linking again means pasting both once more."}
     :handler clear-chair-link
     :display {:label "Unlink the Routine" :style :danger :order 7
               :description "The engine forgets this model's fire URL and token; a seat with no link of its own is not fired again until one is linked"}}

    ;; the runner pool (waymark ticket d16b71bf). A list names links,
    ;; never a credential, so this door records.
    :set_runners
    {:from #{:active} :to :active
     :input [:map
             [:runners {:x-display
                        {:label "Runner links"
                         :help "The runner link ids this model's seats fire through, in order. An empty list hands the fire back to the one link."}}
              [:vector {:max 20} [:string {:min 1 :max 200}]]]
             [:runner_order {:optional true
                             :x-display
                             {:label "Pool order"
                              :help "How a fire picks among the links that may fire. Empty is least used."
                              :choices runner-order-choices}}
              [:maybe (into [:enum] runner-orders)]]]
     :record true
     :guards [a-person-at-the-chair]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The new list replaces the one this model held; another restate puts it back."}
     :handler set-runners
     :display {:label "Runner links" :order 8
               :description "Name the runner links this model's seats fire through, in order"}}

    ;; the boot seed (waymark ticket 4e42b3d4): hidden, engine-written,
    ;; and a no-op on a row that already names a list.
    :seed_runners
    {:from #{:active} :to :active
     :input [:map
             [:runners {:x-display {:hidden true}}
              [:vector {:min 1 :max 20} [:string {:min 1 :max 200}]]]]
     :record true
     :guards [the-engine-seeds-the-pool]
     :edit {:prefill [:runners] :fence false
            :unfenced-reason
            "Written by the boot seed, which read the row in this pass; it writes only a list that is empty."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The engine names the link it seeded from this row; Runner links restates the list."}
     :handler seed-runners
     :display {:label "Runner list seeded"}}}
   :deviations
   ["THE CHAIR'S TWO WRITE FENCES ARE BOTH GUARDS, where the schedule fences its link by omission. `sitter_key`, `fire_url` and `fire_token` are declared on this kind's ONE schema, which is its create door as well — this kind has no create-schema — so a create could carry all three. `key-not-written-by-hand` and `link-not-written-by-hand` are what refuse them, and each refusal names the door that writes the field instead. Both secrets stay `{:secret true}`, so the advertised create body drops them, no form asks, and the usability policies skip them; what a caller gains over silent omission is the sentence."
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
   :states [:open :closed :abandoned]
   :initial :open
   :terminal #{:closed :abandoned}
   :nav :system
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
    ;; THE ROWS THIS SITTING WAS HANDED. The sit writes the ids of its
    ;; walk here, and a second sitting of the same seat opened while
    ;; this one is open walks past them to the next rows. The claim
    ;; ends with the sitting: only an OPEN sitting's rows are read.
    [:walked_rows {:optional true
                   :x-display
                   {:raw true
                    :label "The rows it was handed"
                    :spelled-by-hand "The ids of the walk rows the sit handed this sitting. The sit writes it, and a second open sitting of the same seat is not handed them."}}
     [:maybe [:vector [:string {:max 128}]]]]
    ;; A WAKE THAT WALKED NOTHING. The sit stamps this when the walk it
    ;; hands has no rows at all — an empty queue, a queue whose every
    ;; row another open sitting or a stuck change holds, a seat at a
    ;; wall — so seat health can count an idle wake. An absent
    ;; `walked_rows` cannot: it is also what a sitting the claim never
    ;; wrote to carries. Plain :boolean, `missed`'s spelling, so the
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
     (into [:enum] outcomes)]
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
     [:string {:min 1 :max 120}]]]
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
               (conj [:domain (seat-domain-of (:data row) ctx)]))))
   :filterable {:state #{:eq :in}
                :domain #{:eq}
                :seat #{:eq}
                :member #{:eq}
                :model #{:eq}
                :grant #{:eq}
                :missed #{:eq}
                :closed_by #{:eq :in}
                :outcome #{:eq :in}
                :started_at #{:after :before :range}}
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
     :guards [still-quiet-for-the-sweep]
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
     :guards [the-engine-or-the-persons-tap still-quiet-for-the-sweep]
     ;; no handler: nothing is written. The ending of a sitting
     ;; nobody closed is the ABSENCE of a bill, not a zero one, and
     ;; the counts it already carries are what it did before it was
     ;; lost.
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

(def walk-create-action
  "The action a walk seat's default `wake_on` entry names: a row
  arriving in the queue is the work this seat exists to do."
  "create")

(def judgment-reopen-kind
  "The kind whose `reopen` puts a subject back in a judgment's queue."
  "verdict")

(def judgment-reopen-action
  "The door on it: a verdict taken back with nothing in its place."
  "reopen")

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

  THE DEFAULT FOLLOWS THE FILTER (bead waymark-fp62.12, R-4). When
  the walk's scope entry carries a filter (`walk-filter`), a row
  ARRIVES in the queue two ways: somebody creates it there, and
  somebody moves it there. The computed entry is therefore the walk's
  kind, EVERY action of it, under that filter — the wake consumer
  judges the row that moved against the entry's filter after the
  transition committed (`wakes/moved-under?`), so a row a person
  finalizes into the walk wakes the seat and a row that leaves it does
  not. Every action INCLUDES the birth door: `create` is a door every
  kind serves and no kind lists in `:actions`, and dropping it would
  take away the one wake the unfiltered default already gives.
  `walk-rdef` is the walked kind's own declaration, which is where
  those action names live; called without it this answers the
  `create` default it always has.

  The count wake (R-12.24) asks nothing of the default: the computed
  entry carries no `at_least`, so it stays the transition wake it has
  always been — a walk seat wakes on the row that arrived, and a seat
  that wants a batch says how big a batch is. The settle
  (waymark-fp62.17) asks nothing of it either: the computed entry
  carries no `settle_seconds`, so the default wakes on the row that
  arrived and not after the arrivals stop. A seat that wants the
  trailing edge writes the entry and says how long the quiet is.

  A SEAT THAT SAYS A JUDGMENT HAS A THIRD WAY IN. Its queue is the
  judgment's subjects minus the ones with a standing verdict
  (`judgments/judged-subjects`), so a subject re-enters it when its verdict
  is REOPENED — a transition on kind verdict, not on the walked kind.
  The computed default therefore carries a second entry, `verdict`
  `reopen` under this seat's own judgment, and a reopen wakes the
  seat exactly as a new subject does. A seat that WROTE its `wake_on`
  gets no such entry: what it wrote is what wakes it, and it names
  `{kind verdict, actions [reopen], filter {judgment …}}` itself if
  it wants the reopen too."
  ([seat-row] (effective-wake-on seat-row nil))
  ([seat-row walk-rdef]
   (let [written (get-in seat-row [:data :wake_on])
         walk (some-> (get-in seat-row [:data :walk]) str not-empty)
         judgment (some-> (get-in seat-row [:data :judgment]) str not-empty)
         fm (walk-filter seat-row)
         reopened (when judgment
                    [{:kind judgment-reopen-kind
                      :actions [judgment-reopen-action]
                      :filter {:judgment judgment}}])]
     (cond
       (seq written) (vec written)
       (and walk fm walk-rdef)
       (into [{:kind walk
               :actions (into [walk-create-action]
                              (map (comp name :name))
                              (machine/actions-seq walk-rdef))
               :filter fm}]
             reopened)
       walk (into [{:kind walk :actions [walk-create-action]}] reopened)
       :else []))))

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

(defn open-sittings-for-grant
  "Every open sitting under `grant-id`, newest first, one page of them:
  `open-sitting-for-grant`'s query read past its first row. Every
  sitting of a seat shares the seat's grant, so a sit that must find
  THIS run's sitting among overlapping runs reads them all
  (mcp/reusable-sitting)."
  [eng grant-id]
  (if (and grant-id (get (inv/resources eng) :sitting))
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/query-rows (:storage eng) tx :sitting
                          {:grant (str grant-id) :state :open}
                          {:limit open-sitting-page :newest-first true})))
    []))

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

(defn- call-stamp
  "The moment a counted call lands, as the maintenance writes store it
  (ticket 086307f2): the engine's own clock, so a test that moves the
  clock moves this stamp too."
  [eng]
  (str ((or (:now-fn eng) #(java.time.Instant/now)))))

(defn bump-counter!
  "Add one to an open sitting's `:transitions` or `:refusals`. A
  MAINTENANCE write — document only, version untouched, no transition
  (see the ns docstring). → the new count, or nil when there was
  nothing to count: an unknown id, a sitting already closed, or a
  counter this kind does not keep.

  With a `refusal` ({:type :guard}) on a `:refusals` count, the same
  write stamps `:last_refusal` — the problem type, the guard name when
  one refused, and the moment — so the close can read which law the
  newest refusal was, not only how many there were."
  ([eng sitting-id counter] (bump-counter! eng sitting-id counter nil))
  ([eng sitting-id counter refusal]
   (when (and sitting-id
              (contains? #{:transitions :refusals} counter)
              (get (inv/resources eng) :sitting))
     (store/with-tx (:storage eng)
       (fn [tx]
         (when-some [row (store/load-row (:storage eng) tx :sitting
                                         (str sitting-id) {:for-update true})]
           (when (= :open (:state row))
             (let [n (inc (long (or (get (:data row) counter) 0)))
                   stamp (when (and refusal (= :refusals counter))
                           (cond-> {:type (some-> (:type refusal) str not-empty)
                                    :at (str (java.time.Instant/now))}
                             (some? (:guard refusal))
                             (assoc :guard (let [g (:guard refusal)]
                                             (if (keyword? g) (name g) (str g))))))]
               (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                   (cond-> (assoc (:data row) counter n
                                                  :last_call_at (call-stamp eng))
                                     stamp (assoc :last_refusal stamp))
                                   nil)
               n))))))))

(defn stamp-call!
  "Move an open sitting's `last_call_at` to now and nothing else
  (ticket 900764ce): a READ through the router is activity the idle
  sweep must see, but it is neither a transition nor a refusal, and
  `served` is the MCP door's per-tool ledger. The same MAINTENANCE
  write as `bump-counter!` — document only, version untouched. → the
  stamp, or nil when there was nothing to stamp: no id, an unknown
  id, a sitting already closed, or a kind this engine does not serve."
  [eng sitting-id]
  (when (and sitting-id (get (inv/resources eng) :sitting))
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :sitting
                                        (str sitting-id) {:for-update true})]
          (when (= :open (:state row))
            (let [at (call-stamp eng)]
              (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                  (assoc (:data row) :last_call_at at)
                                  nil)
              at)))))))

(defn claim-sitting!
  "Claim an open sitting for the connector session whose hash is
  `claimant` (ticket 7496403e): `connector_session`, `claimed_at` and
  `last_call_at` move together, when `free?` — handed the row's data
  as this transaction holds it for update — says the row may be taken.
  The same MAINTENANCE write as `stamp-call!`. Two sits racing for one
  row serialise on its lock, and the second reads the first's claim.
  A nil `claimant` stamps the call alone. → the row as claimed, or
  nil: no id, an unknown id, a closed sitting, or a claim `free?`
  refuses."
  [eng sitting-id claimant free?]
  (when (and sitting-id (get (inv/resources eng) :sitting))
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :sitting
                                        (str sitting-id) {:for-update true})]
          (when (and (= :open (:state row)) (free? (:data row)))
            (let [at (call-stamp eng)
                  data (cond-> (assoc (:data row) :last_call_at at)
                         claimant (assoc :connector_session (str claimant)
                                         :claimed_at at))]
              (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                  data nil)
              (assoc row :data data))))))))

(defn stamp-client!
  "Stamp an open sitting with what its connector session's client
  declared at initialize (ticket b9f90987): `client_name`,
  `client_version`, `app_ui`, and `app_tools`, whether that session's
  tool listing carried the app tools as the sit judged it. The sit
  writes all four at the bind and again at each re-sit, so the row
  reads as the session that last sat in it. The same MAINTENANCE write
  as `stamp-call!`. → the row as stamped, or nil: no id, an unknown
  id, or a closed sitting."
  [eng sitting-id {:keys [client-name client-version app-ui app-tools]}]
  (when (and sitting-id (get (inv/resources eng) :sitting))
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :sitting
                                        (str sitting-id) {:for-update true})]
          (when (= :open (:state row))
            (let [said (fn [v]
                         (when-some [s (some-> v str not-empty)]
                           (subs s 0 (min 200 (count s)))))
                  data (assoc (:data row)
                              :client_name (said client-name)
                              :client_version (said client-version)
                              :app_ui (boolean app-ui)
                              :app_tools (boolean app-tools))]
              (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                  data nil)
              (assoc row :data data))))))))

(defn add-cancelled-run!
  "Count one cancelled bench.test run on an open sitting (ticket
  39b2c934). A run with an id is counted once: its id joins
  `cancelled_run_ids`, and a later answer naming the same run leaves
  the count where it is. A run with no id is counted each time, which
  is the most an unnamed run can say. `bump-counter!`'s maintenance
  write. → the count, or nil when there was nothing to count on."
  [eng sitting-id run-id]
  (when (and sitting-id (get (inv/resources eng) :sitting))
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :sitting
                                        (str sitting-id) {:for-update true})]
          (when (= :open (:state row))
            (let [run (some-> run-id str not-empty)
                  seen (vec (:cancelled_run_ids (:data row)))
                  n (long (or (:cancelled_runs (:data row)) 0))]
              (if (and run (some #{run} seen))
                n
                (let [n (inc n)]
                  (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                      (cond-> (assoc (:data row) :cancelled_runs n)
                                        run (assoc :cancelled_run_ids (conj seen run)))
                                      nil)
                  n)))))))))

(defn sitting-transitions
  "The transitions made under a sitting (ticket 39b2c934): the log rows
  whose actor carries the sitting's grant and whose `at` falls in its
  window, `started_at` to `ended_at` (to now, while it is open). The
  log has no sitting column; the grant and the window are the link.
  → a vector, oldest first, or nil for an unknown sitting."
  ([eng sitting-id] (sitting-transitions eng sitting-id {}))
  ([eng sitting-id opts]
   (store/with-tx (:storage eng)
     (fn [tx]
       (when-some [row (store/load-row (:storage eng) tx :sitting
                                       (str sitting-id) {})]
         (let [data (:data row)]
           (when-some [grant (some-> (:grant data) str not-empty)]
             (store/transitions-under-grant
              (:storage eng) tx grant
              (->instant (:started_at data))
              (->instant (:ended_at data))
              opts))))))))

(def ^:private backfill-page
  "The most closed sittings one `backfill-health!` pass reads, newest
  first: a week of every seat's wakes, with room."
  2000)

(defn backfill-health!
  "Stamp `outcome` and `flags` on the closed sittings that carry none
  and started in the last `:backfill-days` days: the rows closed before
  the close judged them (ticket fad586b7). Oldest first, so `rewalk`
  reads earlier sittings this pass has already judged. A MAINTENANCE
  write, `bump-counter!`'s spelling. A judged row is never judged
  again, so a second pass writes nothing. → the number stamped."
  [eng]
  (if (get (inv/resources eng) :sitting)
    (let [st (:storage eng)
          ^java.time.Instant now ((or (:now-fn eng) #(java.time.Instant/now)))
          since (.minus now (java.time.Duration/ofDays
                             (long (:backfill-days health-thresholds))))
          started #(->instant (get-in % [:data :started_at]))
          due (store/with-tx st
                (fn [tx]
                  (->> (store/query-rows st tx :sitting {:state :closed}
                                         {:limit backfill-page
                                          :newest-first true})
                       (filter (fn [row]
                                 (when-some [^java.time.Instant at (started row)]
                                   (and (nil? (get-in row [:data :outcome]))
                                        (.isAfter at since)))))
                       (sort-by started)
                       (mapv (comp str :id)))))]
      (count
       (filterv
        (fn [id]
          (store/with-tx st
            (fn [tx]
              (when-some [row (store/load-row st tx :sitting id
                                              {:for-update true})]
                (let [data (:data row)
                      grant (some-> (:grant data) str not-empty)]
                  (when (and (= :closed (:state row)) (nil? (:outcome data)))
                    (let [moved (when grant
                                  (store/transitions-under-grant
                                   st tx grant
                                   (->instant (:started_at data))
                                   (->instant (:ended_at data))
                                   {}))
                          seat-rows (store/query-rows
                                     st tx :sitting {:seat (str (:seat data))}
                                     {:limit seat-health-page
                                      :newest-first true})
                          seat (some->> (:seat data) str not-empty
                                        (#(store/load-row st tx :seat % {})))]
                      (store/update-data!
                       st tx :sitting id
                       (merge data
                              (sitting-health
                               data moved
                               (earlier-sittings seat-rows id data)
                               (get-in seat [:data :delivers])))
                       nil)
                      true)))))))
        due)))
    0))

;; ── seat health 2: the seat's own rollup (ticket 64a835b4) ──────────
;;
;; A sitting says what ONE wake came to. The seat row says what the
;; last few came to, so a page or a mayor reads every seat's health off
;; the seat collection in one call and opens no sitting to do it.

(defn- divided
  "`num` ÷ `den` to `scale` decimal places, or nil when there is nothing
  to divide by. Exact decimals, `cost-of`'s own rule."
  [num den scale]
  (when (pos? (long den))
    (.divide ^java.math.BigDecimal (bigdec num)
             ^java.math.BigDecimal (bigdec den)
             (int scale) RoundingMode/HALF_UP)))

(defn- flag-key
  "The key one flag is counted under. `refused:<type>` counts as
  `refused`: a problem type is an address, and an address is no key.
  `refused_by:<guard>` keeps its guard, because which law refuses a
  seat out is the thing a reader wants."
  [flag]
  (let [s (if (keyword? flag) (subs (str flag) 1) (str flag))]
    (keyword (if (str/starts-with? s "refused:") "refused" s))))

(defn seat-health
  "A seat's `health` over `sittings`: the documents of its last closed
  and judged sittings, newest first. `merged` is how many of the seat's
  changes merged since the oldest of them started, and `at` the moment
  of the count. Pure. → the map the seat row carries."
  [sittings merged at]
  (let [n (count sittings)
        outcome-of #(some-> (:outcome %) name)
        counts (frequencies (keep outcome-of sittings))
        submits (long (get counts "submitted" 0))
        merged (long (or merged 0))
        cost (transduce (keep #(some-> (:cost_usd %) bigdec)) + 0M sittings)]
    {:sittings n
     :outcomes (into {}
                     (map (fn [o] [(keyword o) (long (get counts o 0))]))
                     outcomes)
     :submit_rate (divided submits n 4)
     :cost_usd cost
     :cost_per_submit (divided cost submits cost-scale)
     :flags (frequencies (map flag-key (mapcat :flags sittings)))
     :merged_prs merged
     :cost_per_merge (divided cost merged cost-scale)
     :last_submit_at (some->> sittings
                              (filter #(= "submitted" (outcome-of %)))
                              first
                              :ended_at
                              ->instant
                              str)
     :last_outcome (some-> (first sittings) outcome-of)
     :computed_at (str at)}))

;; ── seat health 3: a breach is told (ticket 698f6818) ───────────────
;;
;; The rollup says how a seat is doing. An alert says when that is bad
;; enough to tell somebody: each close judges the seat's `health_alerts`
;; over the window it just rolled. A rule that newly breaks files one
;; draft ticket and is recorded as `health.breach`; a rule still broken
;; at the next close files nothing, and one that passes clears the
;; record.

(defn- flag-name
  "A flag as `flag_run` names it: `flag-key`'s spelling, as a string."
  [flag]
  (subs (str (flag-key flag)) 1))

(defn- walked-nothing?
  "Did this sitting end with nothing walked: its sit handed it no rows
  (`stamp-walked-nothing!`) and it moved nothing after. A hook closes
  such a wake within a few turns, so `cut_short` counts beside `idle`."
  [sitting]
  (and (true? (:walked_nothing sitting))
       (contains? #{"idle" "cut_short"} (some-> (:outcome sitting) name))))

(defn health-breach
  "The alert rule a seat's window breaks, or nil when it breaks none.
  `alerts` is the seat's `health_alerts`, laid over
  `health-alert-defaults`; `sittings` are the window's documents, newest
  first, and `health` their rollup (`seat-health`). A run is counted
  from the newest sitting back, so one sitting that is not of the run
  ends it. The rules are judged in one order and the first that breaks
  is the answer. `hidden` is how many rows of the kind the seat walks
  wait outside its grant (`hidden-waiting`), and `walked_nothing_run`
  breaks only while there are some. Pure. → the rule's name."
  [alerts health sittings & [hidden]]
  (let [{:keys [submit_rate_below never_sat_any refused_out_run
                cut_short_run flag_run walked_nothing_run]}
        (merge health-alert-defaults
               (into {} (remove (comp nil? val)) alerts))
        outcome? (fn [o] #(= o (some-> (:outcome %) name)))
        run (fn [pred] (count (take-while pred sittings)))
        reached? (fn [limit n] (and (some? limit) (>= (long n) (long limit))))
        rate (:submit_rate health)]
    (cond
      (and (true? never_sat_any) (some (outcome? "never_sat") sittings))
      "never_sat_any"

      (and (pos? (long (or hidden 0)))
           (reached? walked_nothing_run (run walked-nothing?)))
      "walked_nothing_run"

      (reached? refused_out_run (run (outcome? "refused_out")))
      "refused_out_run"

      (reached? cut_short_run (run (outcome? "cut_short")))
      "cut_short_run"

      :else
      (or (some (fn [[flag limit]]
                  (let [flag (flag-name flag)]
                    (when (reached? limit
                                    (run (fn [s] (some #(= flag (flag-name %))
                                                       (:flags s)))))
                      (str "flag_run:" flag))))
                (sort-by #(flag-name (key %)) flag_run))
          (when (and (some? rate) (some? submit_rate_below)
                     (>= (long (:sittings health)) (long alert-rate-sittings))
                     (neg? (compare (bigdec rate) (bigdec submit_rate_below))))
            "submit_rate_below")))))

;; ── seat health 4: rows wait outside the grant (ticket fd930ff1) ────
;;
;; A sitting never learns of the rows its grant hides. The person who
;; owns the seat should: a seat that keeps walking nothing while rows of
;; the kind it walks wait outside its scope entry's filter is leashed
;; too short. The engine counts those rows with its own hand, and the
;; count goes to the ticket and to no answer a sitting reads.

(def ^:private hidden-scan-limit
  "The most rows of the walked kind one count reads. A count that
  reaches it says that many or more."
  200)

(defn- hidden-str
  "A state or a field's value as a filter spells it."
  [v]
  (if (keyword? v) (name v) (str v)))

(defn- hidden-waiting
  "The rows of the kind this seat walks that wait where its grant does
  not reach: in the state its walk reads — the filter's own, or the
  kind's default, or any state that is not terminal — and outside the
  other fields of its scope entry's filter (`walk-filter`). Read
  through the store and not through the seat's grant. A seat whose
  entry filters by no field hides none, and a judgment seat walks the
  judgment's queue and is not counted. Best effort.
  → {:count :state :plural :fields}, or nil when none waits."
  [eng tx seat-row]
  (try
    (let [kind (some-> (get-in seat-row [:data :walk]) str not-empty keyword)
          rdef (get (inv/resources eng) kind)
          flt (into {}
                    (map (fn [[f v]] [(name f) (hidden-str v)]))
                    (walk-filter seat-row))
          fields (dissoc flt "state")]
      (when (and rdef (seq fields)
                 (nil? (some-> (get-in seat-row [:data :judgment]) str not-empty)))
        (let [state (or (not-empty (get flt "state"))
                        (some-> (get-in rdef [:default-filters :state])
                                hidden-str not-empty))
              terminal (into #{} (map hidden-str) (:terminal rdef))
              waits? (fn [row]
                       (or (some? state)
                           (not (contains? terminal (hidden-str (:state row))))))
              admitted? (fn [row]
                          (every? (fn [[f v]]
                                    (= v (some-> (get-in row [:data (keyword f)])
                                                 hidden-str)))
                                  fields))
              n (count (filter #(and (waits? %) (not (admitted? %)))
                               (store/query-rows (:storage eng) tx kind
                                                 (if state {:state (keyword state)} {})
                                                 {:limit hidden-scan-limit})))]
          (when (pos? n)
            {:count n
             :state state
             :plural (:plural rdef)
             :fields (vec (sort (keys fields)))}))))
    (catch Exception _ nil)))

(defn- hidden-sentence
  "What the person is told of a `walked_nothing_run` breach: how many
  times the seat walked nothing and how many rows waited outside its
  grant. The count and the kind, and no row's id."
  [seat sittings {n :count :keys [state plural fields]}]
  (str (get-in seat [:data :name]) " walked nothing "
       (count (take-while walked-nothing? sittings)) " times while "
       n " " (when state (str state " ")) plural
       " sat outside its grant (" (str/join ", " fields)
       " filter or scope)."))

(defn- breach-detail
  "What the ticket says: the seat, the rule, and each sitting of the
  window with what it came to, its flags and its link. A
  `walked_nothing_run` breach opens with `hidden-sentence`."
  [seat rule sittings hidden]
  (let [data (:data seat)]
    (str (when (and hidden (= "walked_nothing_run" rule))
           (str (hidden-sentence seat sittings hidden) "\n\n"))
         "Seat " (:name data) " (/api/seats/" (:id seat)
         ") broke its health alert `" rule "` at "
         (get-in data [:health :breach :at]) ".\n\n"
         "Its last " (count sittings) " sittings, newest first:\n"
         (str/join
          "\n"
          (map (fn [s]
                 (str "- /api/sittings/" (:id s) " · "
                      (or (some-> (:outcome s) name) "not judged")
                      (when-some [flags (seq (:flags s))]
                        (str " · "
                             (str/join ", " (map #(if (keyword? %)
                                                    (subs (str %) 1)
                                                    (str %))
                                                 flags))))))
               sittings))
         (when (true? (:breaker_open data))
           (str "\n\nThe breaker is open (`breaker_open`): the seat wakes one sitting at a time, and its max_open_sittings stays "
                (:max_open_sittings data)
                ". `close_breaker` on the seat lets it wake them all again.")))))

(defn- file-breach-ticket!
  "The draft ticket a new breach earns, filed as the engine
  (`judgments/file-ticket!`'s posture). Best effort: an engine that
  serves no `ticket` kind files none, and a refusal leaves the breach
  recorded without one. → the ticket's id, or nil."
  [eng seat sittings hidden]
  (when (get (inv/resources eng) :ticket)
    (let [{:keys [rule at]} (get-in seat [:data :health :breach])]
      (try
        (some-> (inv/create! eng :ticket
                             {:title (str "seat " (get-in seat [:data :name])
                                          " health breach: " rule)
                              :detail (breach-detail seat rule sittings hidden)
                              :repo "ckopsa/waymark"
                              :type "bug"
                              :priority 1}
                             {:principal seats-actor
                              :idempotency-key (str "seat-health:" (:id seat)
                                                    ":" rule ":" at)})
                :row :id str)
        (catch Exception _ nil)))))

(defn- stamp-breach-ticket!
  "Write the filed ticket's id beside the breach it was filed for, when
  that breach still stands. The rollup's own MAINTENANCE write.
  → the seat's `health` as written, or nil."
  [eng seat-id rule ticket]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (when-some [seat (store/load-row st tx :seat seat-id
                                         {:for-update true})]
          (when (= rule (get-in seat [:data :health :breach :rule]))
            (let [health (assoc-in (get-in seat [:data :health])
                                   [:breach :ticket] ticket)]
              (store/update-data! st tx :seat seat-id
                                  (assoc (:data seat) :health health)
                                  (:next-flip-at seat))
              health)))))))

(defn- merged-since
  "How many of this seat's changes merged at or after `since`: the rows
  of the `change` kind in `merged` whose author is the seat's name,
  read by the moment each row last moved — a merged change is over, so
  that moment is the merge unless a later maintenance write touched the
  row. An engine that serves no such kind has merged none."
  [eng tx seat-row ^java.time.Instant since]
  (let [author (some-> (get-in seat-row [:data :name]) str not-empty)]
    (if (and since author (get (inv/resources eng) :change))
      (count
       (filter (fn [row]
                 (when-some [^java.time.Instant at (->instant (:updated-at row))]
                   (not (.isBefore at since))))
               (store/query-rows (:storage eng) tx :change
                                 {:state :merged :author author}
                                 {:limit seat-health-page :newest-first true})))
      0)))

(defn roll-health!
  "Write the seat's `health` again, over its last `health_window` closed
  sittings that carry an outcome (`seat-health`). A sitting closed
  before the close judged it is not in the window. A MAINTENANCE write,
  `bump-counter!`'s spelling: document only, version untouched, no
  transition. Best-effort — a rollup that cannot be counted leaves the
  last one standing and never fails the close that asked for it.

  SEAT HEALTH 3: the same write judges the seat's alerts over the
  window (`health-breach`). A rule that newly breaks is recorded as
  `health.breach`, opens the breaker when the seat asks for one and
  runs several sittings at once, and files one ticket after the write
  commits. The same rule at the next close keeps its record and files
  nothing; no rule clears it.

  SEAT HEALTH 4: when the newest sitting walked nothing, the rows of
  the walked kind that wait outside the seat's grant are counted in the
  same transaction (`hidden-waiting`) for `walked_nothing_run`. The
  count rides to the ticket and is not written on the seat row.
  → the map written, or nil when there was nothing to write."
  [eng seat-id]
  (when (and seat-id
             (get (inv/resources eng) :seat)
             (get (inv/resources eng) :sitting))
    (try
      (let [st (:storage eng)
            seat-id (str seat-id)
            started #(->instant (:started_at %))]
        (when-some [{:keys [seat sittings health new? hidden]}
                    (store/with-tx st
                      (fn [tx]
                        (when-some [seat (store/load-row st tx :seat seat-id
                                                         {:for-update true})]
                          (let [window (max 1 (long (or (get-in seat [:data :health_window])
                                                        (:window health-thresholds))))
                                sittings (->> (store/query-rows
                                               st tx :sitting
                                               {:seat seat-id :state :closed}
                                               {:limit seat-health-page :newest-first true})
                                              ;; the id rides along for the
                                              ;; ticket's links
                                              (map #(assoc (:data %) :id (:id %)))
                                              (filter #(and (some? (:outcome %)) (started %)))
                                              (sort-by started #(compare %2 %1))
                                              (take window)
                                              vec)
                                at (call-stamp eng)
                                health (seat-health
                                        sittings
                                        (merged-since eng tx seat
                                                      (some-> (peek sittings) started))
                                        at)
                                was (get-in seat [:data :health :breach])
                                hidden (when (walked-nothing? (first sittings))
                                         (hidden-waiting eng tx seat))
                                rule (health-breach (get-in seat [:data :health_alerts])
                                                    health sittings (:count hidden))
                                new? (and (some? rule)
                                          (not= rule (some-> (:rule was) str)))
                                trip? (and new?
                                           (true? (get-in seat [:data :health_breaker]))
                                           (< 1 (long (or (get-in seat [:data :max_open_sittings])
                                                          1))))
                                health (cond-> health
                                         rule (assoc :breach
                                                     (if new?
                                                       {:rule rule :at (str at)}
                                                       was)))
                                data (cond-> (assoc (:data seat) :health health)
                                       trip? (assoc :breaker_open true))]
                            (store/update-data! st tx :seat seat-id data
                                                (:next-flip-at seat))
                            {:seat (assoc seat :data data)
                             :sittings sittings
                             :health health
                             :new? new?
                             :hidden hidden}))))]
          ;; the ticket is its own write, after the rollup's commit
          (or (when new?
                (when-some [ticket (file-breach-ticket! eng seat sittings hidden)]
                  (stamp-breach-ticket! eng seat-id
                                        (get-in health [:breach :rule])
                                        ticket)))
              health)))
      (catch Exception _ nil))))

(defn after-write
  "The engine's `:maintain` hook, this module's arm (seat health 2): a
  committed write that leaves a sitting closed rolls its seat's
  `health`. It answers nil for every write, so the composition keeps
  the row the passes before it decided on."
  [eng kind _action-name res]
  (when (and (= :sitting kind)
             (= "closed" (some-> (get-in res [:row :state]) name)))
    (roll-health! eng (get-in res [:row :data :seat])))
  nil)

(defn- a-persons-write?
  "A logged actor a person answers for: a human, or a held call a person
  allowed (`allowed_by`) — the ledger's own reading (R-11.3)."
  [actor]
  (or (= "human" (some-> (:type actor) name))
      (some? (:allowed_by actor))))

(defn count-correction!
  "R-11.3 on the sitting's own row: when the transition just committed on
  `kind`/`resource-id` is a person's and the one before it was a
  sitter's, the CLOSED sitting that wore that transition's grant gains
  one `corrections` and the row's id in `corrected_rows`. The same
  MAINTENANCE write as `bump-counter!` — document only, no transition.
  A second person's transition reads a person before it and adds
  nothing; a row no sitter touched has no grant to find. Framework
  kinds (`:nav :system`) are left out, as the ledger leaves them out.
  → the new count, or nil when nothing was corrected."
  [eng kind resource-id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (when (and resource-id rdef
               ;; a sitting's own doors are not the seat's work, as
               ;; count-committed! reads them
               (not= :sitting kind)
               (not= :system (:nav rdef))
               (get (inv/resources eng) :sitting))
      (let [[after before] (store/with-tx st
                             (fn [tx]
                               (store/transitions st tx {:kind kind
                                                         :resource-id (str resource-id)}
                                                  {:newest-first true :limit 2})))
            gid (get-in before [:actor :grant])]
        (when (and before gid
                   (a-persons-write? (:actor after))
                   (not (a-persons-write? (:actor before))))
          (store/with-tx st
            (fn [tx]
              ;; the grant's newest sitting, whatever its state: one still
              ;; open is not credited, and an older closed one is not either
              (when-some [s (some-> (store/query-rows st tx :sitting
                                                      {:grant (str gid)}
                                                      {:limit 1 :newest-first true})
                                    first
                                    (as-> s (when (= "closed" (some-> (:state s) name)) s)))]
                (when-some [row (store/load-row st tx :sitting (str (:id s))
                                                {:for-update true})]
                  (let [n (inc (long (or (get-in row [:data :corrections]) 0)))
                        rid (str resource-id)
                        ids (vec (get-in row [:data :corrected_rows]))]
                    (store/update-data! st tx :sitting (str (:id s))
                                        (assoc (:data row)
                                               :corrections n
                                               :corrected_rows (if (some #{rid} ids)
                                                                 ids
                                                                 (conj ids rid)))
                                        nil)
                    n))))))))))

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
                                     (-> (:data row)
                                         (assoc-in [:served k] line)
                                         (assoc :last_call_at (call-stamp eng)))
                                     nil)
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

(defn seat-halt-at-the-wall!
  "The week's fuel wall met by a wake it held or by a sit it halted,
  and not by a request: `seat-halt!` with `budget_reached` and the
  wall's own sentence, cut to the door's 240. A seat whose wakes are
  all held never makes a request, so without this the router never
  writes its halt and nobody subscribed to `mark_halted` hears it.
  Once per wall, as `seat-halt!` is. Best effort: the hold or the sit
  is still itself when the seat moved under it. → true when written."
  [eng seat-id detail]
  (try
    (seat-halt! eng seat-id "budget_reached"
                (some-> detail str not-empty
                        (as-> d (subs d 0 (min 240 (count d))))))
    (catch Exception e
      (binding [*out* *err*]
        (println "waymark10 seats: seat" seat-id
                 "could not record its fuel wall -" (ex-message e)))
      false)))

(defn seat-clear-budget-halt!
  "Lift a `budget_reached` halt, and only that one, for a caller that
  has just seen the week's fuel wall no longer holds. A halt of any
  other wall is the router's to lift. Best effort, as above. → true
  when a halt was cleared."
  [eng seat-id]
  (try
    (if (= "budget_reached"
           (str (get-in (seat-row eng seat-id) [:data :halt :reason])))
      (seat-clear-halt! eng seat-id)
      false)
    (catch Exception e
      (binding [*out* *err*]
        (println "waymark10 seats: seat" seat-id
                 "could not lift its fuel wall -" (ex-message e)))
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

(defn- key-matches?
  "Is this row's `sitter_key` exactly the key the caller presented?

  `MessageDigest/isEqual` over UTF-8 bytes — constant time in the
  length of the two arrays, so a caller cannot walk a key one
  character at a time off the clock. A row holding no key matches
  nothing, which is why a blank key must never reach here."
  [row ^bytes wanted]
  (boolean
   (when-some [held (some-> (get-in row [:data :sitter_key]) str not-empty)]
     (MessageDigest/isEqual wanted (.getBytes held StandardCharsets/UTF_8)))))

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
                 (filter #(key-matches? % wanted))
                 first)))))))

(defn seat-named
  "The ACTIVE seat this token names, or nil — an id first, then the
  seat's own name (R-4 of waymark-fp62.7.23).

  The id first because a Routine handed one spells it exactly, and
  the name because that is what a person writes into a seat's
  instructions. ACTIVE only, `seat-by-key`'s own rule and its reason:
  a parked, merged or retired seat serves nothing, and a sit that
  landed in one would be a sitting nobody can spend."
  [eng named]
  (when-some [named (some-> named str str/trim not-empty)]
    (when (get (inv/resources eng) :seat)
      (store/with-tx (:storage eng)
        (fn [tx]
          (let [st (:storage eng)
                by-id (store/load-row st tx :seat named {})]
            (or (when (= :active (:state by-id)) by-id)
                (first (store/query-rows st tx :seat
                                         {:name named :state :active}
                                         {:limit 1})))))))))

(defn standing-key?
  "Is this key the seat's OWN, or its chair's?

  TWO STANDING KEYS OPEN ONE SEAT. The seat's own `sitter_key` is the
  first, and it is R-12.12 unchanged. The second is the CHAIR'S: the
  `sitter_key` of the first model in `held_for`. That second one is
  what makes one Routine for each model possible. The model's key sits
  in every seat that model is the chair of, and the name says which
  seat this firing is.

  The two are asked as one question because the sit asks them for one
  reason. A standing key is spent by nothing. A firing's key (R-12.37)
  is spent by the sit that presents it. The compare is `key-matches?`,
  constant time, for its own reason."
  [eng seat-row key]
  (boolean
   (when-some [key (some-> key str not-empty)]
     (when seat-row
       (let [wanted (.getBytes ^String key StandardCharsets/UTF_8)]
         (or (key-matches? seat-row wanted)
             (when-some [chair (and (get (inv/resources eng) :model)
                                    (chair-of seat-row))]
               (some-> (store/with-tx (:storage eng)
                         (fn [tx]
                           (store/load-row (:storage eng) tx :model
                                           chair {})))
                       (key-matches? wanted)))))))))

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

(defonce ^:private ^SecureRandom key-random (SecureRandom.))

(def ^:private key-ceiling
  "How many unspent keys one seat keeps. A seat fires far fewer times
  than this inside one idle window, so the ceiling never drops a key a
  live session is about to present. It is the fence against a row that
  grows without a bound when a Routine is dark and nobody sits."
  32)

(defn- instant-of
  "An instant, however the row spells it: a stored string, or an
  Instant already. Unparsable is nil, which reads as never."
  [v]
  (cond
    (instance? Instant v) v
    (some-> v str not-empty) (try (Instant/parse (str v))
                                  (catch Exception _ nil))
    :else nil))

(defn- mint-key
  "128 bits of real randomness, base64url, unpadded. It is
  `mcp/new-session-id`'s own shape. A machine mints it for
  `offer_key`'s own reason: 22 characters of randomness is what no
  hand types."
  []
  (let [b (byte-array 16)]
    (.nextBytes key-random b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn key-hash
  "The SHA-256 of one key, base64url, unpadded.

  IT IS THE ONLY THING THE ROW KEEPS. The key is on the wire one time,
  in the fire text, and a seat row read by anybody at all hands over
  nothing a session could present. A blank key hashes to nil, so a row
  that holds no key matches nothing."
  [key]
  (when-some [key (some-> key str not-empty)]
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                     (.digest (MessageDigest/getInstance "SHA-256")
                              (.getBytes ^String key StandardCharsets/UTF_8)))))

(defn- live-keys
  "The entries of this row that still answer at `now`: the keys no sit
  has spent, whose moment has not passed. An entry the engine cannot
  date is dropped, because a key it cannot expire is a key it must not
  answer."
  [row ^Instant now]
  (into []
        (filter (fn [e]
                  (when-some [^Instant at (instant-of (:expires_at e))]
                    (.isAfter at now))))
        (get-in row [:data :fire_keys])))

(defn- fire-key-entry
  "The live entry of this row that answers `key` at `now`, or nil. The
  compare is `MessageDigest/isEqual` over the two hashes, constant
  time in their length, for `key-matches?`'s own reason."
  [row key ^Instant now]
  (when-some [wanted (key-hash key)]
    (let [wanted (.getBytes ^String wanted StandardCharsets/UTF_8)]
      (first (filter (fn [e]
                       (MessageDigest/isEqual
                        wanted
                        (.getBytes (str (:hash e)) StandardCharsets/UTF_8)))
                     (live-keys row now))))))

(def ^:private uuid-in
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(def ^:private short-id-in
  "An 8-hex word: the first group of a row id, as a person writes it."
  #"(?<![0-9A-Za-z-])[0-9a-fA-F]{8}(?![0-9A-Za-z-])")

(def ^:private short-id-words
  "How many 8-hex words of one prose `named-row` looks up."
  8)

(defn- short-id-row
  "The id of the ONE row whose id opens with the 8-hex `prefix`, or nil
  (ticket 26c7967a). The rows are those of the kind the seat walks and,
  for a seat that walks tickets, the changes, which `named-walk-row`
  reads back to their ticket. No row, or several, names nothing."
  [eng walk prefix]
  (let [st (:storage eng)
        prefix (str/lower-case prefix)
        conds [{:target :id :op :>=
                :value (str prefix "-0000-0000-0000-000000000000")}
               {:target :id :op :<=
                :value (str prefix "-ffff-ffff-ffff-ffffffffffff")}]
        ids (try
              (into []
                    (comp (filter #(get (inv/resources eng) %))
                          (mapcat (fn [kind]
                                    (store/with-tx st
                                      (fn [tx]
                                        (store/ids-matching st tx kind
                                                            conds 2)))))
                          (map str)
                          (filter #(str/starts-with? % prefix)))
                    (distinct (cond-> [(keyword walk)]
                                (= "ticket" walk) (conj :change))))
              (catch Exception _ nil))]
    (when (= 1 (count ids))
      (first ids))))

(defn- named-row
  "The id of the walk row a fire's text names, or nil. A wake's text is
  the transition as JSON (`wakes/wake-text`): the kind and the row id.
  Only a row of the kind this seat WALKS counts, or, for a seat that
  walks tickets, a change, which the sit reads back to the ticket it was
  born from (`named-walk-row`). A person's prose names the first row id
  it holds (ticket 7af7d506), and the sit hands that row only when it is
  such a row. Prose with no whole id names the row of its first 8-hex
  word that opens exactly one row's id (`short-id-row`, ticket
  26c7967a), when `eng` is given to look it up. Any other text — prose
  with no id, a count wake's count — names nothing."
  ([seat-row text] (named-row nil seat-row text))
  ([eng seat-row text]
   (when-some [walk (some-> (get-in seat-row [:data :walk]) str not-empty)]
     (when-some [s (some-> text str str/trim not-empty)]
       (if (str/starts-with? s "{")
         (let [m (try (wire/read-json s) (catch Exception _ nil))
               kind (when (map? m) (str (:kind m)))]
           (when (or (= walk kind)
                     (and (= "ticket" walk) (= "change" kind)))
             (some-> (:id m) str not-empty)))
         (or (re-find uuid-in s)
             (when eng
               (some #(short-id-row eng walk %)
                     (take short-id-words
                           (distinct (re-seq short-id-in s)))))))))))

(defn hold-fire-key!
  "Mint the key ONE fire carries, and keep its hash on the seat row.
  The key comes back, for the fire text to carry. Nil comes back when
  this seat has no instructions.

  A SEAT WITH NO INSTRUCTIONS FIRES AS IT ALWAYS DID. Its Routine
  holds a key of its own, and the composed text is the person's prose
  alone (R-12.35). A key no session would read is a key not worth
  minting.

  The write is a MAINTENANCE write, `bump-counter!`'s own spelling and
  its reason: a transition for each fire would put the record of a
  credential in the log, beside the fire that is already there. The
  row is read FOR UPDATE inside the write's own transaction, so a
  restate that lands at the same moment is not lost, and two fires of
  one seat do not write over each other's key.

  The expired entries go at the same moment, and the newest keys stay,
  up to `key-ceiling`.

  `text` is the fire's own text. When it is a wake's text naming a row
  of the kind the seat walks, the entry keeps that row's id (`:row`),
  so the sit that spends the key knows which row the run was sent to
  walk (`fire-key-row`)."
  ([eng seat-row at] (hold-fire-key! eng seat-row at nil))
  ([eng seat-row at text]
  (when (and seat-row
             (some-> (get-in seat-row [:data :instructions]) str not-empty)
             (get (inv/resources eng) :seat))
    (let [^Instant at (or (instant-of at) ((:now-fn eng)))
          key (mint-key)
          ttl (long (or (get-in seat-row [:data :sitting_idle_seconds])
                        default-idle-seconds))
          named (named-row eng seat-row text)
          entry (cond-> {:hash (key-hash key)
                         :expires_at (str (.plusSeconds at ttl))
                         :fired_at (str at)}
                  named (assoc :row named))]
      (store/with-tx (:storage eng)
        (fn [tx]
          (when-some [row (store/load-row (:storage eng) tx :seat
                                          (str (:id seat-row))
                                          {:for-update true})]
            (let [kept (vec (take-last (dec (long key-ceiling))
                                       (live-keys row at)))]
              (store/update-data! (:storage eng) tx :seat (str (:id seat-row))
                                  (assoc (:data row) :fire_keys
                                         (conj kept entry))
                                  (:next-flip-at row))
              key))))))))

(defn fire-key-held?
  "Does this seat hold an unspent, unexpired key for `key`?

  A READ, and it spends nothing. The sit asks it to learn which seat a
  key answers for, and it spends the key only after every other wall
  has passed."
  [eng seat-row key]
  (boolean (and seat-row
                (some? (fire-key-entry seat-row key ((:now-fn eng)))))))

(defn fire-key-row
  "The walk row the fire that minted `key` named, or nil (`named-row`).
  A READ, like `fire-key-held?`: the sit asks it BEFORE it spends the
  key, since the spend takes the entry off the row."
  [eng seat-row key]
  (when seat-row
    (some-> (fire-key-entry seat-row key ((:now-fn eng))) :row str not-empty)))

(defn spend-fire-key!
  "Spend the key of one firing: take its hash off the seat row, so the
  next sit that presents it is refused. → true when this call is the
  one that spent it, and nil when this key is no firing's key of this
  seat.

  ONE KEY OPENS ONE SIT, and this is where that holds. The row is read
  FOR UPDATE and written in the same transaction, so two sessions that
  present one key at the same moment do not both come away with it.
  The second reads the row the first already wrote, finds no entry,
  and is refused with the sentence of R-12.14.

  A standing key reaches here too, and it answers nil. `standing-key?`
  is what the caller asks first, and a key the seat answers for by its
  own credential is spent by nothing."
  [eng seat-row key]
  (when (and seat-row (get (inv/resources eng) :seat))
    (let [now ((:now-fn eng))]
      (store/with-tx (:storage eng)
        (fn [tx]
          (when-some [row (store/load-row (:storage eng) tx :seat
                                          (str (:id seat-row))
                                          {:for-update true})]
            (when-some [entry (fire-key-entry row key now)]
              (let [kept (into [] (remove #(= (str (:hash %))
                                              (str (:hash entry))))
                               (live-keys row now))]
                (store/update-data! (:storage eng) tx :seat (str (:id seat-row))
                                    (assoc (:data row) :fire_keys kept)
                                    (:next-flip-at row))
                true))))))))

(defn keep-fire-key!
  "Keep the trace of a spent firing's key on the sitting it opened: its
  hash, never the key (R-12.37). A MAINTENANCE write, `spend-fire-key!`'s
  own spelling and its reason. Only an OPEN sitting takes the trace, and
  it answers only while the sitting stays open, so the close, the
  tally-driven close, the abandon and the sweep all leave a key that
  answers nothing. → true when the trace was written."
  [eng sitting-id key]
  (when-some [h (key-hash key)]
    (when (and sitting-id (get (inv/resources eng) :sitting))
      (store/with-tx (:storage eng)
        (fn [tx]
          (when-some [row (store/load-row (:storage eng) tx :sitting
                                          (str sitting-id) {:for-update true})]
            (when (= :open (:state row))
              (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                  (assoc (:data row) :fire_key_hash h) nil)
              true)))))))

;; ── the inbox's key ─────────────────────────────────────────────────

(def default-inbox
  "What an INTERACTIVE seat that states no `inbox` hears (epic
  3ad250ef): every move of the kinds a person at a seat works with. It
  is derived where the inbox is judged and never stored, and the door
  still narrows it to the kinds the seat's scope reads."
  {:only {:ticket [] :change [] :held_call [] :approval_request []
          :seat [] :sitting []}})

(defn inbox-of
  "The inbox this seat's sittings pull: the one it states, else
  `default-inbox` for an interactive seat, else nil. A fired seat has
  an inbox only when it states one.

  An inbox that states `cues` and no `only` keeps the default `only`,
  so an interactive seat adds cues without restating every kind it
  hears."
  [seat-row]
  (let [stated (get-in seat-row [:data :inbox])
        default (when (interactive-seat? seat-row) default-inbox)]
    (cond
      (nil? stated) default
      (some? (:only stated)) stated
      :else (merge default (dissoc stated :only)))))

(def inbox-keys-max
  "How many inbox keys of one sitting answer at one time. A same-session
  sit past it drops the oldest."
  12)

(defn- inbox-key-hashes
  "The hashes of the inbox keys this sitting's data holds, newest
  first."
  [data]
  (->> (cons (:inbox_key_hash data) (:inbox_keys_earlier data))
       (keep #(some-> % str not-empty))
       vec))

(defn issue-inbox-key!
  "Mint a fresh inbox key for this sitting, keep its hash on the
  sitting, and answer the key. → the key, or nil when the seat has no
  inbox (`inbox-of`) or the sitting is no longer open.

  `transcripts/issue-key!`'s shape and its reason: each sit mints a new
  key, the row keeps the hash alone, and the write is a maintenance
  write, so the record of a credential is not in the log. Every key
  dies with the sitting, because `inbox-sitting-by-key` reads open
  sittings only.

  A SIT OF THE SAME HARNESS SESSION STOPS NO KEY, `with-feed-token`'s
  way: when `harness-session` is the one stamped on the sitting, the
  keys before this one are kept beside it (the newest `inbox-keys-max`
  in all), so a stream that is using one is not cut. Any other sit of
  the sitting replaces them: the old keys stop answering."
  ([eng seat-row sitting-row] (issue-inbox-key! eng seat-row sitting-row nil))
  ([eng seat-row sitting-row harness-session]
   (when (and seat-row sitting-row
              (some? (inbox-of seat-row))
              (get (inv/resources eng) :sitting))
     (let [key (mint-key)
           harness (some-> harness-session str str/trim not-empty)]
       (store/with-tx (:storage eng)
         (fn [tx]
           (when-some [row (store/load-row (:storage eng) tx :sitting
                                           (str (:id sitting-row)) {:for-update true})]
             (when (= :open (:state row))
               (let [data (:data row)
                     same? (and harness
                                (= harness (some-> (:harness_session data) str not-empty)))]
                 (store/update-data! (:storage eng) tx :sitting (str (:id row))
                                     (assoc data
                                            :inbox_key_hash (key-hash key)
                                            :inbox_keys_earlier
                                            (if same?
                                              (vec (take (dec (long inbox-keys-max))
                                                         (inbox-key-hashes data)))
                                              []))
                                     (:next-flip-at row)))
               key))))))))

(defn inbox-url
  "The absolute address of the inbox door, from the origin the sit
  arrived under."
  [origin]
  (str (str/replace (str origin) #"/+$" "") "/api/-/sittings/inbox"))

(def ^:private inbox-sitting-page
  "The most open sittings the inbox door reads for a key: every seat's
  open sittings, which are a handful while the sweep keeps up."
  500)

(defn inbox-sitting-by-key
  "The open sitting this inbox key belongs to, raw, or nil for a bad
  key, a key a later sit replaced, or a sitting that has ended. A key
  a same-session sit kept (`issue-inbox-key!`) finds it as the newest
  does.

  THE KEY IS FOUND BY READING THE OPEN SITTINGS, for
  `transcripts/transcript-by-key`'s reason: `inbox_key_hash` is
  :secret, and a :secret field may never be :filterable."
  [eng key]
  (when-some [wanted (key-hash key)]
    (when (get (inv/resources eng) :sitting)
      (let [wanted (.getBytes ^String wanted StandardCharsets/UTF_8)]
        (->> (store/with-tx (:storage eng)
               (fn [tx]
                 (store/query-rows (:storage eng) tx :sitting {:state :open}
                                   {:limit inbox-sitting-page})))
             (filter (fn [r]
                       (some (fn [held]
                               (MessageDigest/isEqual
                                wanted
                                (.getBytes ^String held StandardCharsets/UTF_8)))
                             (inbox-key-hashes (:data r)))))
             first)))))

(def feed-token-seconds
  "How long a feed token answers while its sitting stays open: 35
  minutes. A sitting that runs longer is answered the next one by the
  key check door (`renew-feed-token!`), or sits again for a fresh one."
  2100)

(def feed-tokens-max
  "How many feed tokens of one sitting answer at one time. A sit past
  it drops the oldest; the key check door past it renews nothing."
  12)

(defn- live-feed-tokens
  "The feed tokens this sitting's data holds that have not reached
  their end, as `{:hash :expires_at}`, newest first."
  [data ^Instant now]
  (->> (cons {:hash (:feed_token_hash data)
              :expires_at (:feed_token_expires_at data)}
             (:feed_tokens_earlier data))
       (filter (fn [{:keys [hash expires_at]}]
                 (and (some-> hash str not-empty)
                      (when-some [^Instant until (->instant expires_at)]
                        (.isBefore now until)))))
       (mapv #(select-keys % [:hash :expires_at]))))

(defn- feed-token-held
  "The live feed token of this sitting's data whose hash is `wanted`,
  compared in constant time, or nil."
  [data ^bytes wanted now]
  (->> (live-feed-tokens data now)
       (filter (fn [{:keys [hash]}]
                 (MessageDigest/isEqual
                  wanted
                  (.getBytes (str hash) StandardCharsets/UTF_8))))
       first))

(defn- with-feed-token
  "This sitting's data with `token` as its newest feed token. The
  tokens before it that have not ended are kept beside it, so a token
  in use answers until its own end: the newest `feed-tokens-max` in
  all."
  [data token expires now]
  (assoc data
         :feed_token_hash (key-hash token)
         :feed_token_expires_at expires
         :feed_tokens_earlier (vec (take (dec (long feed-tokens-max))
                                         (live-feed-tokens data now)))))

(defn- feed-sitting-by-token
  "The OPEN sitting that holds a live feed token of this hash, raw, or
  nil."
  [eng tx ^bytes wanted now]
  (->> (store/query-rows (:storage eng) tx :sitting
                         {:state :open}
                         {:limit inbox-sitting-page})
       (filter #(feed-token-held (:data %) wanted now))
       first))

(defn issue-feed-token!
  "Mint a fresh feed token for this sitting, keep its hash and its end
  on the sitting, and answer `{:url :token :expires_at :note}`. → nil
  when the seat declares no https `feed_url` or the sitting is no
  longer open.

  EACH SIT MINTS A NEW TOKEN AND STOPS NONE. A sit that reuses an open
  sitting leaves the tokens before it answering until their own ends
  (`with-feed-token`), so a stream that is using one is not cut. The
  row keeps the hashes alone, so a sit cannot answer an earlier token
  again. The write is a maintenance write, `issue-inbox-key!`'s way,
  so the record of a credential is not in the log. Every token dies
  with the sitting, because `feed-seat-by-token` reads open sittings
  only, and at its `expires_at` when the sitting outlives it.

  IT IS NOT A KEY. Only the key check door reads `feed_token_hash`, so
  the token sits in no seat, closes no sitting and pulls no inbox."
  [eng seat-row sitting-row]
  (when-some [url (some-> seat-row (get-in [:data :feed_url]) str not-empty)]
    (when (and sitting-row
               (str/starts-with? url "https://")
               (get (inv/resources eng) :sitting))
      (let [token (mint-key)
            ^Instant now ((:now-fn eng))
            expires (str (.plusSeconds now (long feed-token-seconds)))]
        (store/with-tx (:storage eng)
          (fn [tx]
            (when-some [row (store/load-row (:storage eng) tx :sitting
                                            (str (:id sitting-row)) {:for-update true})]
              (when (= :open (:state row))
                (store/update-data! (:storage eng) tx :sitting (str (:id row))
                                    (with-feed-token (:data row) token expires now)
                                    (:next-flip-at row))
                {:url url
                 :token token
                 :expires_at expires
                 :note (str "A read-only feed token for " url ", valid until "
                            expires " or the close of this sitting, safe to "
                            "send as `Authorization: Bearer` to that URL only.")}))))))))

(defn feed-seat-by-token
  "The ACTIVE seat whose open sitting was answered this feed token, raw,
  or nil for a bad token, a token past its end, or a sitting that has
  ended. A token a later sit or a renewal followed answers until its
  own end (`live-feed-tokens`).

  THE TOKEN IS FOUND BY READING THE OPEN SITTINGS, for
  `inbox-sitting-by-key`'s reason: `feed_token_hash` is :secret, and a
  :secret field may never be :filterable."
  [eng token]
  (when-some [wanted (key-hash token)]
    (when (and (get (inv/resources eng) :sitting)
               (get (inv/resources eng) :seat))
      (let [wanted (.getBytes ^String wanted StandardCharsets/UTF_8)
            ^Instant now ((:now-fn eng))]
        (store/with-tx (:storage eng)
          (fn [tx]
            (when-some [sitting (feed-sitting-by-token eng tx wanted now)]
              (when-some [seat (some->> (get-in sitting [:data :seat]) str not-empty
                                        (#(store/load-row (:storage eng) tx :seat % {})))]
                (when (= :active (:state seat))
                  seat)))))))))

(defn renew-feed-token!
  "The next feed token for the open sitting that holds `token`, as
  `{:token :expires_at}`, when `token` is live and has less than half
  its life left. → nil for every other token, for a seat that is not
  active, and for a sitting that already holds `feed-tokens-max` live
  tokens.

  THE OLD TOKEN IS NOT STOPPED. It answers until its own end, so the
  stream that holds it has no gap between the two (`with-feed-token`).
  The write is `issue-feed-token!`'s maintenance write. An ask that
  repeats past the half mints again, because the row keeps hashes and
  cannot answer the same next token twice; `feed-tokens-max` bounds it."
  [eng token]
  (when-some [wanted (key-hash token)]
    (when (and (get (inv/resources eng) :sitting)
               (get (inv/resources eng) :seat))
      (let [wanted (.getBytes ^String wanted StandardCharsets/UTF_8)
            ^Instant now ((:now-fn eng))
            half (quot (long feed-token-seconds) 2)]
        (store/with-tx (:storage eng)
          (fn [tx]
            (when-some [found (feed-sitting-by-token eng tx wanted now)]
              (when-some [row (store/load-row (:storage eng) tx :sitting
                                              (str (:id found)) {:for-update true})]
                (let [^Instant until (some-> (feed-token-held (:data row) wanted now)
                                             :expires_at
                                             ->instant)
                      seat (some->> (get-in row [:data :seat]) str not-empty
                                    (#(store/load-row (:storage eng) tx :seat % {})))]
                  (when (and (= :open (:state row))
                             until
                             (.isBefore until (.plusSeconds now half))
                             (= :active (:state seat))
                             (< (count (live-feed-tokens (:data row) now))
                                (long feed-tokens-max)))
                    (let [next-token (mint-key)
                          expires (str (.plusSeconds now (long feed-token-seconds)))]
                      (store/update-data! (:storage eng) tx :sitting (str (:id row))
                                          (with-feed-token (:data row) next-token expires now)
                                          (:next-flip-at row))
                      {:token next-token :expires_at expires})))))))))))

(def ^:private release-grace-default
  "The grace, in seconds, a seat that names no `release_grace_seconds`
  gives the rows of a sitting that closed."
  120)

(defn- walked-rdef
  "The rdef of the kind this seat walks, or nil."
  [eng seat-row]
  (some->> (get-in seat-row [:data :walk]) str not-empty keyword
           (get (inv/resources eng))))

(defn- graced-rows
  "The walk rows a sitting of this seat CLOSED within the seat's
  `release_grace_seconds` walked (ticket f6c8d5ce). A close can land
  while the run that sat is still mid-call — its hook fired early, or
  its last answer is in flight — and a fire a minute later must not
  take its ticket out from under it. An abandoned sitting was swept,
  not closed, and holds nothing; the sitting `sitting-id` names is
  left out, as `claimed-rows` leaves it. A row whose work is over
  (`machine/work-over?` under the walked kind's `rdef`) was finished,
  not released, and is not held. → a set of ids."
  [st tx seat-row rdef sitting-id now]
  (let [grace (long (or (get-in seat-row [:data :release_grace_seconds])
                        release-grace-default))
        now (instant-of now)]
    (if-not (and seat-row now (pos? grace))
      #{}
      (let [since (.minusSeconds ^Instant now grace)]
        (into #{}
              (comp (remove #(= (str sitting-id) (str (:id %))))
                    (filter #(when-some [ended (instant-of
                                                (get-in % [:data :ended_at]))]
                               (.isAfter ^Instant ended since)))
                    (mapcat #(get-in % [:data :walked_rows]))
                    (keep #(some-> % str not-empty))
                    (remove #(when rdef
                               (when-some [r (store/load-row
                                              st tx (keyword (str (get-in seat-row [:data :walk])))
                                              % {})]
                                 (machine/work-over?
                                  rdef (inv/decode-row rdef r))))))
              (store/query-rows st tx :sitting
                                {:seat (str (:id seat-row)) :state :closed}
                                {:limit open-sitting-page
                                 :newest-first true}))))))

(defn grace-lifts-at
  "The moment the first of `ids` that a CLOSED sitting of this seat
  still holds through its grace (`graced-rows`) is handed on: that
  sitting's `ended_at` plus the seat's `release_grace_seconds`. A wake
  that finds its walk empty only because its row rests there is
  deferred to this moment, not spent on a run that sits and finds
  nothing (ticket 1a4038bf). → an Instant after `now`, or nil when
  none of `ids` is held by a grace."
  [eng seat-row ids now]
  (let [ids (into #{} (keep #(some-> % str not-empty)) ids)
        grace (long (or (get-in seat-row [:data :release_grace_seconds])
                        release-grace-default))
        now (instant-of now)
        st (:storage eng)]
    (when (and seat-row now (pos? grace) (seq ids)
               (get (inv/resources eng) :sitting))
      (store/with-tx st
        (fn [tx]
          (let [held (into #{} (filter ids)
                           (graced-rows st tx seat-row (walked-rdef eng seat-row)
                                        nil now))]
            (when (seq held)
              (->> (store/query-rows st tx :sitting
                                     {:seat (str (:id seat-row)) :state :closed}
                                     {:limit open-sitting-page
                                      :newest-first true})
                   (keep (fn [s]
                           (when (some #(contains? held (str %))
                                       (get-in s [:data :walked_rows]))
                             (some-> (instant-of (get-in s [:data :ended_at]))
                                     (.plusSeconds grace)))))
                   (filter #(.isAfter ^Instant % ^Instant now))
                   sort
                   first))))))))

(defn claimed-rows
  "The walk row ids the OTHER open sittings of this seat were handed:
  the rows a second run of the seat must not walk again. A fire and a
  wake that land together start two runs, and without this both sits
  answer the same first row, so both work one branch and the next row
  waits. The claim ends with the sitting — an abandoned or swept
  sitting holds nothing, and a closed one holds its rows only through
  the seat's grace (`graced-rows`) — and the sitting `sitting-id`
  names is left out, so a re-sit is handed its own rows again. → a
  set of ids."
  [eng seat-id sitting-id]
  (if (and seat-id (get (inv/resources eng) :sitting))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (into (let [seat-row (store/load-row st tx :seat (str seat-id) {})]
                  (graced-rows st tx seat-row (walked-rdef eng seat-row)
                               sitting-id ((:now-fn eng))))
                (comp (remove #(= (str sitting-id) (str (:id %))))
                      (mapcat #(get-in % [:data :walked_rows]))
                      (keep #(some-> % str not-empty)))
                (store/query-rows st tx :sitting
                                  {:seat (str seat-id) :state :open}
                                  {:limit open-sitting-page
                                   :newest-first true})))))
    #{}))

(defn graced-row-ids
  "The part of `claimed-rows` a CLOSED sitting of this seat holds
  through the seat's release grace (`graced-rows`), the sitting
  `sitting-id` left out, so a walk the grace emptied can say so
  (ticket ae64b57c). → a set of ids."
  [eng seat-id sitting-id]
  (if (and seat-id (get (inv/resources eng) :sitting))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (let [seat-row (store/load-row st tx :seat (str seat-id) {})]
            (graced-rows st tx seat-row (walked-rdef eng seat-row)
                         sitting-id ((:now-fn eng)))))))
    #{}))

;; ── the rows a sit leaves out of its walk ───────────────────────────

(def ^:private stuck-scan-limit
  "How many stuck changes one walk reads to leave their tickets out. A
  stuck change waits for a person, and a house that works holds few."
  200)

(def ^:private live-change-scan-limit
  "How many change rows one lookup of a ticket's live change reads."
  20)

(def ^:private groomed-walk-prefix
  "What `born_from` starts with for a change built for a ticket."
  "ticket:")

(defn- latest-transition
  "The newest transition of one row through `action`, or nil."
  [eng kind id action]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (some (fn [tr] (when (= action (some-> (:action tr) name)) tr))
              (store/transitions st tx {:kind kind :resource-id (str id)}
                                 {:newest-first true}))))))

(defn groom-after-stall
  "The ticket's newest `groom` when it is newer than the change's newest
  `stall`, or nil: a person has read the stall and stands behind the
  ticket again. A change that was never stalled, or a ticket not groomed
  since, answers nil."
  [eng change ticket-id]
  (let [groom (latest-transition eng :ticket ticket-id "groom")
        stall (latest-transition eng :change (:id change) "stall")]
    (when (and groom stall (> (long (:id groom)) (long (:id stall))))
      groom)))

(defn stuck-walk-reasons
  "The tickets in a ticket walk whose change is stuck and
  waits for a person (ticket 6bdaf6fe): a `stuck` change born from the
  ticket, no live change beside it, and no groom since its stall. The
  walk leaves them out as it leaves a claimed row out, so a queue of
  only such tickets answers an empty walk and no wake spends a sitting
  on saying it is stuck. A groom after the stall puts the ticket back,
  and the sit then unsticks its change. A stuck change is one whatever
  state it was stuck from: `open`, `submitted` or `failing` (ticket
  60c2ec22).

  A ticket whose change is `submitted` is left out too (ticket
  60c2ec22): its round is in review and its landing may still run, so
  a second run of the seat has nothing to build on it, and a submit
  there only finds a clean worktree.

  Only a change in the ticket's own repository counts, when the ticket
  names one (ticket 0eba219c, as 80a8e60b for the named walk): each
  candidate ticket's repo is loaded once, within the scan limit.

  → {ticket-id reason}: each withheld ticket with the sentence the sit
  answers for it (ticket 87c928e9), so a seat handed an empty walk
  can say which row was held back and why. Empty for any other walk
  and for an engine that serves no change."
  [eng walk]
  (if-some [rdef (when (= "ticket" (str walk))
                   (get (inv/resources eng) :change))]
    (let [st (:storage eng)
          changes (fn [where limit]
                    (->> (store/with-tx st
                           (fn [tx]
                             (store/query-rows st tx :change where
                                               {:limit limit})))
                         (map #(inv/decode-row rdef %))))
          tdef (get (inv/resources eng) :ticket)
          repo-of (memoize
                   (fn [ticket-id]
                     (when tdef
                       (try
                         (some-> (some->> (store/with-tx st
                                            (fn [tx]
                                              (store/load-row st tx :ticket
                                                              ticket-id {})))
                                          (inv/decode-row tdef))
                                 (get-in [:data :repo]) str not-empty)
                         (catch Exception _ nil)))))
          ours? (fn [ticket-id change]
                  (let [repo (repo-of ticket-id)]
                    (or (nil? repo)
                        (= repo (some-> (get-in change [:data :repository])
                                        str)))))
          live? (fn [born ticket-id]
                  (some #(and (contains? #{:open :submitted :failing}
                                         (some-> (:state %) name keyword))
                              (ours? ticket-id %))
                        (changes {:born_from born} live-change-scan-limit)))
          ticket-of (fn [change]
                      (let [born (str (get-in change [:data :born_from]))]
                        (when (str/starts-with? born groomed-walk-prefix)
                          (not-empty (subs born (count groomed-walk-prefix))))))]
      (into (into {} (keep (fn [change]
                             (when-some [ticket-id (ticket-of change)]
                               (when (ours? ticket-id change)
                                 [ticket-id (str "change " (:id change)
                                                 " is submitted and in review")]))))
                  (changes {:state "submitted"} stuck-scan-limit))
            (keep (fn [change]
                    (let [born (str (get-in change [:data :born_from]))]
                      (when (str/starts-with? born groomed-walk-prefix)
                        (when-some [ticket-id (not-empty
                                               (subs born (count groomed-walk-prefix)))]
                          (when (and (ours? ticket-id change)
                                     (not (live? born ticket-id))
                                     (nil? (groom-after-stall eng change
                                                              ticket-id)))
                            [ticket-id (str "change " (:id change)
                                            " is stuck and waits for a person")]))))))
            (changes {:state "stuck"} stuck-scan-limit)))
    {}))

(defn stuck-walk-rows
  "The ids of `stuck-walk-reasons`, as a set."
  [eng walk]
  (set (keys (stuck-walk-reasons eng walk))))

(defn named-walk-row
  "The walk row a fire's text named, as the sit reads it (ticket
  7af7d506): the id itself when it is a row of the kind the seat walks,
  and for a ticket walk the ticket a named CHANGE was born from, so a
  fire that names either hands the ticket and the change beside it. Nil
  for an id that is neither."
  [eng walk id]
  (when-some [id (some-> id str not-empty)]
    (let [walk (str walk)
          row-of (fn [kind]
                   (when-some [rdef (get (inv/resources eng) kind)]
                     (try
                       (some->> (store/with-tx (:storage eng)
                                  (fn [tx]
                                    (store/load-row (:storage eng) tx kind id
                                                    {})))
                                (inv/decode-row rdef))
                       (catch Exception _ nil))))]
      (cond
        (and (seq walk) (row-of (keyword walk))) id

        (= "ticket" walk)
        (when-some [change (row-of :change)]
          (let [born (str (get-in change [:data :born_from]))]
            (when (str/starts-with? born groomed-walk-prefix)
              (not-empty (subs born (count groomed-walk-prefix))))))))))

(defn fire-deferred-until
  "When a fire whose `text` names a walk row (`named-row`, read back to
  its ticket by `named-walk-row`) must wait: the moment that row's
  release grace lifts (`grace-lifts-at`), when only a CLOSED sitting's
  grace holds it (ticket afb445d4). A run fired now would sit, be told
  the row is held, and stop. A row an OPEN sitting of the seat holds is
  the sit's to say, as it was, and a free row fires at once. → an
  Instant after `now`, or nil."
  [eng seat-row text now]
  (when-some [named (some->> (named-row eng seat-row text)
                             (named-walk-row eng (get-in seat-row [:data :walk])))]
    (let [st (:storage eng)
          open (store/with-tx st
                 (fn [tx]
                   (into #{}
                         (comp (mapcat #(get-in % [:data :walked_rows]))
                               (keep #(some-> % str not-empty)))
                         (store/query-rows st tx :sitting
                                           {:seat (str (:id seat-row)) :state :open}
                                           {:limit open-sitting-page
                                            :newest-first true}))))]
      (when-not (contains? open named)
        (grace-lifts-at eng seat-row [named] now)))))

(defn fire-names
  "The walk row a fire's `text` names, as the sit reads it (`named-row`,
  read back to its ticket by `named-walk-row`), or nil when it names
  none."
  [eng seat-row text]
  (some->> (named-row eng seat-row text)
           (named-walk-row eng (get-in seat-row [:data :walk]))))

(defn open-walked-rows
  "The walk row ids the OPEN sittings of this seat hold, as a set.
  `claimed-rows` less the rows a closed sitting's grace holds: those
  are `fire-deferred-until`'s to say."
  [eng seat-id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (into #{}
              (comp (mapcat #(get-in % [:data :walked_rows]))
                    (keep #(some-> % str not-empty)))
              (store/query-rows st tx :sitting
                                {:seat (str seat-id) :state :open}
                                {:limit open-sitting-page
                                 :newest-first true}))))))

(defn- ticket-ended?
  "Has the ticket `id` ended, `done` or `dropped`? False for a ticket
  the store does not hold."
  [eng id]
  (boolean
   (when-some [rdef (get (inv/resources eng) :ticket)]
     (let [st (:storage eng)]
       (try
         (some->> (store/with-tx st
                    (fn [tx] (store/load-row st tx :ticket (str id) {})))
                  (inv/decode-row rdef)
                  :state name keyword
                  (contains? #{:done :dropped}))
         (catch Exception _ false))))))

(defn named-beside-a-live-change?
  "Does a live change — open, submitted, failing or stuck — stand beside
  the ticket a fire named? Such a ticket is walked whatever its own
  state (ticket 7af7d506): a seat fired on a ticket in review is handed
  it and its change, and a ticket whose change merged or closed is not.
  A ticket that ended is not either (ticket 458d65c5): its ending
  closed its changes, and a leftover is no work to hand. False for any
  other walk."
  [eng walk id]
  (boolean
   (when-some [rdef (when (and (= "ticket" (str walk))
                               (not (ticket-ended? eng id)))
                      (get (inv/resources eng) :change))]
     (let [st (:storage eng)]
       (some #(contains? #{:open :submitted :failing :stuck}
                         (some-> (:state %) name keyword))
             (map #(inv/decode-row rdef %)
                  (store/with-tx st
                    (fn [tx]
                      (store/query-rows st tx :change
                                        {:born_from (str groomed-walk-prefix
                                                         id)}
                                        {:limit live-change-scan-limit})))))))))

(defn named-beside-a-submitted-change?
  "Does the ticket a fire named stand in one of `states`, a set of state
  names, while a change born from it is `submitted`? Only a change in
  the ticket's own repository counts, when the ticket names one (ticket
  80a8e60b). False for any other walk."
  [eng walk id states]
  (boolean
   (when-some [rdef (when (= "ticket" (str walk))
                      (get (inv/resources eng) :change))]
     (when-some [tdef (get (inv/resources eng) :ticket)]
       (let [st (:storage eng)
             ticket (try
                      (some->> (store/with-tx st
                                 (fn [tx]
                                   (store/load-row st tx :ticket (str id) {})))
                               (inv/decode-row tdef))
                      (catch Exception _ nil))]
         (when (contains? states (some-> (:state ticket) name))
           (some (let [repo (some-> (get-in ticket [:data :repo]) str not-empty)]
                   #(and (= "submitted" (some-> (:state %) name))
                         (or (nil? repo)
                             (= repo (some-> (get-in % [:data :repository]) str)))))
                 (map #(inv/decode-row rdef %)
                      (store/with-tx st
                        (fn [tx]
                          (store/query-rows st tx :change
                                            {:born_from (str groomed-walk-prefix
                                                             id)}
                                            {:limit live-change-scan-limit})))))))))))

(defn named-open-beside-a-submitted-change?
  "Is the ticket a fire named `open` while a change born from it is
  `submitted` (ticket 6ca380da)? The round is in the house's hands, so
  the named walk withholds it as the plain walk does (ticket 60c2ec22).
  A groom, unblock or resume that puts a stuck pull request back under
  review sends its ticket out for review after it (ticket 7e01dbe5); a
  ticket whose follow-up was refused, or whose change was submitted by
  another path, is the one left open here. A ticket in review is still
  handed by name (ticket 7af7d506)."
  [eng walk id]
  (named-beside-a-submitted-change? eng walk id #{"open"}))

(defn unwalkable-rows
  "The walk row ids a sit of this seat would not hand now: the rows
  another open sitting holds (`claimed-rows`) and the tickets whose
  change is stuck (`stuck-walk-rows`). The sit subtracts them from its
  page and the wakes from their count, so a wake never fires a run
  whose sit walks nothing (ticket e031e479). `sitting-id` is the
  sitting whose own rows stay walkable, or nil. → a set of ids."
  [eng seat-row sitting-id]
  (into (claimed-rows eng (:id seat-row) sitting-id)
        (stuck-walk-rows eng (get-in seat-row [:data :walk]))))

(defn claim-rows!
  "Write the walk row ids this sit handed on the sitting it opened, so
  `claimed-rows` keeps them from a second open sitting of the seat. A
  MAINTENANCE write, `keep-fire-key!`'s spelling: only an OPEN sitting
  takes the claim. → true when it was written."
  [eng sitting-id row-ids]
  (let [ids (into [] (keep #(some-> % str not-empty)) row-ids)]
    (when (and sitting-id (seq ids) (get (inv/resources eng) :sitting))
      (store/with-tx (:storage eng)
        (fn [tx]
          (when-some [row (store/load-row (:storage eng) tx :sitting
                                          (str sitting-id) {:for-update true})]
            (when (= :open (:state row))
              (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                  (assoc (:data row) :walked_rows ids) nil)
              true)))))))

(defn claim-rows-atomically!
  "`claim-rows!`, with the read of the other sittings' claims IN THE
  SAME TRANSACTION as the write. The seat row is read FOR UPDATE first,
  so every claim of one seat runs one at a time: two sits of the seat
  at the same instant cannot both find a row free and both write it.

  The claim is all or nothing. When a row asked for is already held by
  another open sitting of the seat, nothing is written, and the answer
  says which rows are held, so the sit reads its walk again past them.
  → {:claimed? bool :taken #{ids other open sittings hold}}."
  [eng seat-id sitting-id row-ids]
  (let [ids (into [] (keep #(some-> % str not-empty)) row-ids)
        st (:storage eng)]
    (if-not (and seat-id sitting-id (get (inv/resources eng) :sitting))
      {:claimed? true :taken #{}}
      (store/with-tx st
        (fn [tx]
          (let [seat-row (store/load-row st tx :seat (str seat-id) {:for-update true})
                open (store/query-rows st tx :sitting
                                       {:seat (str seat-id) :state :open}
                                       {:limit open-sitting-page
                                        :newest-first true})
                taken (into (graced-rows st tx seat-row (walked-rdef eng seat-row)
                                         sitting-id ((:now-fn eng)))
                            (comp (remove #(= (str sitting-id) (str (:id %))))
                                  (mapcat #(get-in % [:data :walked_rows]))
                                  (keep #(some-> % str not-empty)))
                            open)
                mine (first (filter #(= (str sitting-id) (str (:id %))) open))]
            (cond
              (some taken ids) {:claimed? false :taken taken}

              (and mine (seq ids))
              (do (store/update-data! st tx :sitting (str sitting-id)
                                      (assoc (:data mine) :walked_rows ids) nil)
                  {:claimed? true :taken taken})

              :else {:claimed? true :taken taken})))))))

(defn stamp-walked-nothing!
  "Stamp the sitting whose sit was let walk and handed it NO rows — an
  empty queue, a queue whose every row another open sitting or a stuck
  change holds — so seat health counts a wake that had nothing to do
  rather than reading an absent `walked_rows`, which a sitting the
  claim never wrote to carries too. A re-sit that IS handed a row takes
  the stamp back off. A MAINTENANCE write, `claim-rows!`'s spelling:
  only an OPEN sitting takes it, and only a change is written.

  `why` rides beside the stamp as `walked_nothing_why`: the rule that
  left each row out. `halted` is the sit's own `halted` block, and a
  halted sit is stamped with IT and not `walked_nothing` — it was not
  let walk, and a reader must not take the wall for a walk bug (ticket
  ae64b57c). → true when it was written."
  ([eng sitting-id nothing?]
   (stamp-walked-nothing! eng sitting-id nothing? nil))
  ([eng sitting-id nothing? {:keys [why halted]}]
   (when (and sitting-id (get (inv/resources eng) :sitting))
     (store/with-tx (:storage eng)
       (fn [tx]
         (when-some [row (store/load-row (:storage eng) tx :sitting
                                         (str sitting-id) {:for-update true})]
           (let [mark (cond
                        (seq halted) {:halted (halt-mark-of halted)}
                        nothing? (cond-> {:walked_nothing true}
                                   why (assoc :walked_nothing_why why))
                        :else {})
                 data (merge (dissoc (:data row) :walked_nothing
                                     :walked_nothing_why :halted)
                             mark)]
             (when (and (= :open (:state row)) (not= data (:data row)))
               (store/update-data! (:storage eng) tx :sitting (str sitting-id)
                                   data nil)
               true))))))))

(defn stamp-halted-schedule!
  "The seat's schedule, told the wall its sit found (ticket ae64b57c):
  `halted` beside `last_halted_wake`, so the schedule says which wall
  held the seat and when it lifts, and not only that a wake waits. A
  sit clear of the wall takes it off. A MAINTENANCE write, as
  `stamp-walked-nothing!`. → true when it was written."
  [eng seat-id halted]
  (when (and seat-id (get (inv/resources eng) :schedule))
    (let [st (:storage eng)]
      (store/with-tx st
        (fn [tx]
          (when-some [row (first (store/query-rows st tx :schedule
                                                   {:seat (str seat-id)}
                                                   {:limit 1}))]
            (let [data (if (seq halted)
                         (assoc (:data row) :halted (halt-mark-of halted))
                         (dissoc (:data row) :halted))]
              (when (not= data (:data row))
                (store/update-data! st tx :schedule (:id row) data
                                    (:next-flip-at row))
                true))))))))

(defn open-sitting-count
  "How many sittings of this seat are OPEN now: the runs the seat's
  `max_open_sittings` is judged against (`wakes/wake-seat!`)."
  [eng seat-id]
  (if (and seat-id (get (inv/resources eng) :sitting))
    (count (store/with-tx (:storage eng)
             (fn [tx]
               (store/query-rows (:storage eng) tx :sitting
                                 {:seat (str seat-id) :state :open}
                                 {:limit open-sitting-page}))))
    0))

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
          (let [wanted (.getBytes ^String wanted StandardCharsets/UTF_8)
                rows (store/with-tx (:storage eng)
                       (fn [tx]
                         (store/query-rows (:storage eng) tx :sitting
                                           {:seat (str (:id seat)) :state :open}
                                           {:limit open-sitting-page
                                            :newest-first true})))
                hit (first
                     (filter (fn [r]
                               (and (= harness (some-> (get-in r [:data :harness_session])
                                                       str not-empty))
                                    (when-some [held (some-> (get-in r [:data :fire_key_hash])
                                                             str not-empty)]
                                      (MessageDigest/isEqual
                                       wanted
                                       (.getBytes ^String held StandardCharsets/UTF_8)))))
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
