(ns waymark10.server.schedules
  "The schedule (spec-seat.md §12): one row per seat, owned by the
  engine, mirrored OUT to the harness's own scheduler.

  R-12.0's ruling is that the seat is the only thing a person manages.
  Nobody edits the Routine by hand, and the house writes no loop
  script; instead the seat's `schedule` ref points at a row that
  RECORDS the means by which a sitting is created, and an adapter
  keeps the provider's copy equal to it. The four things the copy
  holds are the seat's `name`, the seat's `cadence_seconds` spelled as
  a cron, the schedule's `model`, and the fixed pointer prompt of
  R-12.3 — and the prompt is frozen at creation, so a `restate` of the
  charter changes what the next firing DOES with no push at all.

  ── why this is not a mirror kind ──────────────────────────────────

  The calendar is the precedent (R-12.0 says so) and the precedent
  does not fit; the three reasons are recorded in `:deviations` and
  here at length, because the next person to read §12 will ask.

  1. THE AUTHORITY POINTS THE OTHER WAY. `server/mirror` is for a
     kind whose truth lives OUTSIDE: a pull wins, a disagreement is
     `conflicted`, and a person resolves it. Here the row is the
     truth and the copy is downstream of it. R-12.3 asks for a
     read-back that REPORTS — \"writes any difference into `drift`\"
     — and explicitly not for repair. A mirror would have healed the
     row from the Routine, which is the drift this spec exists to
     catch rather than adopt.
  2. THE MACHINE IS NOT THE SYNC MACHINE. `mirror/declaration`
     refuses a kind that declares `:states`/`:initial` — a mirror's
     lifecycle is fresh/stale/unreachable/conflicted and domain state
     lives in data. R-12.1 names four states and they are about the
     COPY's life, not about a sync's: `pending` (no copy yet),
     `live`, `paused`, `broken` — and this kind adds `held`, the
     provider's own pause of the Routine (ticket f1918540).
  3. THE OPERATIONS ARE NOT push/pull. `MirrorAdapter` writes
     documents; a scheduler is paused, resumed and deleted, and
     `calendar10/source` had to hang `delete-event!` off the side of
     the protocol to say so in its own docstring. Six operations,
     named, are honest where four plus an escape hatch are not.

  So the bookkeeping is ordinary data on an ordinary kind with hidden
  system doors — the mirror's own posture for its sync writes,
  borrowed without the machine. The provider adapter that once wrote
  the copy is gone: ticket 274a4150 disconnected it, because the
  Routines API is fire-only, and ticket 28814005 dropped the fields
  only it wrote (`drop-mirror-fields!` takes them off a stored row).

  ── how a seat reaches the provider ────────────────────────────────

  A durable log consumer (`consumers/register-consumer!`, the
  webhook deliverer's at-least-once discipline) reads the transition
  log and reacts to the seat's own doors:

      create           → mint the schedule row, write it onto the
                         seat, create the copy
      restate          → push the copy again (name, cron, model)
      park             → pause the copy
      unpark           → resume it
      merge, retire    → delete it

  plus the schedule's own `restate`, which is the one human door here
  (R-12.1: a person may restate the model, for a substitute day).

  At-least-once means every one of those runs twice sooner or later,
  so each is idempotent by construction: the CREATE carries the
  schedule row's id as the provider's `client_token`, so a replay
  that lost its `claim` gets the same copy back rather than a second
  one; update/pause/resume/delete are PUT-shaped; and a transition
  whose from-state has already been reached is skipped rather than
  invoked (a 409 inside a consumer PARKS the drain, and parking on a
  replay would stop the world).

  Wave two may move the push to the wire-boundary effect seam
  (`approval-effects!`, waymark-442.14, named by R-12.2). The
  consumer is what is built, and it is the more honest of the two
  under a crash: the log is the record, and a post-commit effect that
  dies takes its push with it.

  ── the credential is a power (R-12.11) ────────────────────────────

  The provider's token is the engine's, never a grant's:
  `write-capability` is the registry row that NAMES it
  (`schedule.write`) — a power this engine enforces itself — and a
  deployment's boot seed ensures it the way workqueue10's
  `ensure-capabilities!` ensures the Gate tokens. Nothing grants it —
  naming it is what makes the power auditable.

  ── what is not built here ─────────────────────────────────────────

  - `jules` and `cron` are in the enum and have no fire adapter.
  - R-12.6's ceiling (`sitting_budget_tokens` through the provider's
    own knob) is not pushed: the Routines API is not pinned, and
    R-12.6 itself names the fallback — the walk's `rows_per_firing`
    caps the firing and the sitting's note says when the cap was hit.
    One field on the create payload when the real API is known.
  - the one-row-per-session form of R-12.9 (firing the schedule with
    a row id as its text) is the source's business, not the seat's.
  - ONE ROW PER SEAT is a create guard reading the collection, not a
    `:unique` index. `roles/one-spelling` records the same choice and
    the same follow-up: the guard keeps the nicer refusal sentence,
    the index would close the racing-creates window. Nothing races
    here yet — the consumer is elected and mints these rows alone —
    which is why the index can wait and the sentence cannot.

  ── the fire link (R-12.18 to R-12.20) ─────────────────────────────

  The Routines API is FIRE-ONLY: one endpoint starts a run, and no
  endpoint makes a Routine, changes one, lists them or reads one
  back. So for that provider the copy above cannot exist. A person
  makes the Routine by hand, once, and pastes its fire URL and its
  token into a runner link whose `pools` name this row; the engine
  fires it from then on and never writes it.

  A row that fires through a runner pool is a row a person manages:
  the engine never writes its Routine, and retiring the seat still
  ends the row. The runner link is the whole of the engine's knowledge
  of that Routine (ticket 9fa42626).

  ── one Routine for each MODEL (waymark-fp62.7.23) ─────────────────

  A Routine per seat is a Routine a person makes, links and keeps in
  step by hand, and a step down the ladder was a second one. So a
  pool names the model as well — the seat's CHAIR, the first of its
  `held_for` — and a schedule with no pool of its own fires through
  the chair's (`pool-of`). The chair's row is left alone by the
  adapter exactly as a pooled row is, for the same reason: that
  Routine was made by hand, one row over.

  What tells the firings apart is then the TEXT, not the Routine.
  `fire-text` composes it from the seat row — the seat's own
  instructions, the line that names the seat, and the person's prose
  in the block those instructions point at — so the words a firing
  reads are on a row a person can see and restate, and never pasted
  into a Routine where they can go stale in the dark. The transition
  log keeps the prose alone, as it always did.

  AND THE KEY (R-12.37). The text carries one line more, under the
  line that names the seat: the key of this one firing, minted here by
  `seats/hold-fire-key!` and kept on the seat row as a hash. The
  Routine then holds no secret of its own, so a Routine made without
  one is no longer a firing with nothing to sit with. The key is on
  the wire one time and it is in no record at all.

  The fire itself is a second seam, `FireAdapter`, with one
  operation. It runs AFTER the commit, in this same consumer, when
  the seat's own `fire` door is heard: the door refuses what a door
  can refuse (parked, halted, unlinked, a bare agent) and the
  provider's own answer lands on this row as a state and a note —
  `fired` on a 2xx, `held` on a 400, `live` with `retry_after` on
  a throttle, `broken` with the sentence on a 401, a 403 or a 404.
  A replay is deduped by `last_fired_at`, and
  nothing here ever re-throws: a throwing consumer parks the drain.

  ── the engine opt a deployment owes ───────────────────────────────

  `fire-adapter-of` reads `(:fire-adapter eng)` first, and the
  real one needs no environment at all: the URL and the token are
  fields of the row it is firing."
  (:require [clojure.string :as str]
            [waymark10.time :refer [instant-of]]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def system-actor
  "The actor every engine-written schedule transition wears."
  (t/principal {:id "waymark10-schedules" :type :system
                :display "Schedules"}))

;; ── the cron spelling ───────────────────────────────────────────────

(def ^:private minute-steps
  "The divisors of 60 a `*/n` minute field may carry. A step that does
  not divide 60 fires unevenly across the hour — 0,25,50,0 — which is
  not the cadence anybody asked for."
  [30 20 15 12 10 6 5 4 3 2 1])

(def ^:private hour-steps
  "The divisors of 24, same rule, for the hour field."
  [12 8 6 4 3 2 1])

(defn- largest-step [steps n]
  (or (first (filter #(<= (long %) (long n)) steps)) 1))

(defn cron-of
  "`cadence_seconds` as a 5-field cron.

  The rule is AT LEAST THIS OFTEN: a cadence that cron cannot say
  exactly rounds DOWN to a step it can say, so a seat never fires
  less often than its own field promises. 900 is `*/15 * * * *`, 3600
  is `0 * * * *`, 21600 is `0 */6 * * *`, 86400 is `0 0 * * *`.

  The corners, each decided rather than fallen into:
  - under a minute is `* * * * *`; cron has no finer field, and the
    honest answer to \"every 10 seconds\" is every minute.
  - a step must divide its field (60 minutes, 24 hours), so 5400
    seconds is hourly rather than a `*/1.5` nobody can spell.
  - seven days is `0 0 * * 0` — a real week, on Sundays — because
    `*/7` on the day of month restarts every month and drifts.
  - past a fortnight the day-of-month step is already approximate and
    past a month cron cannot say it at all: the coarsest this
    function will spell is `0 0 1 * *`, monthly."
  [cadence-seconds]
  (let [s (long (or cadence-seconds 0))]
    (cond
      (<= s 60) "* * * * *"

      (< s 3600)
      (let [step (largest-step minute-steps (quot s 60))]
        (if (= 1 step) "* * * * *" (str "*/" step " * * * *")))

      (< s 86400)
      (let [step (largest-step hour-steps (quot s 3600))]
        (if (= 1 step) "0 * * * *" (str "0 */" step " * * *")))

      :else
      (let [d (quot s 86400)]
        (cond
          (= 1 d) "0 0 * * *"
          (= 7 d) "0 0 * * 0"
          (<= d 15) (str "0 0 */" d " * *")
          :else "0 0 1 * *")))))

;; ── the fixed pointer prompt (R-12.3) ───────────────────────────────

(defn pointer-prompt
  "The copy's prompt, verbatim from R-12.3 with the seat's name in it.

  It is a pointer and a walk rule and nothing else — no charter, no
  rules file, no document from docs/. R-12.10 is the reason: a prompt
  that carries law ahead of time is a prompt that grows every time
  the engine fails to speak at a door, and a growing prompt is what
  stops the ladder descending. It never changes after the copy is
  made."
  [seat-name]
  (str "You sit in the seat `" seat-name "`. Read the seat row with "
       "`waymark_get` and do what its charter says. Take only the doors "
       "the envelope offers. When the seat says halted or parked, say "
       "why and stop."))

;; ── the capability (R-12.11) ────────────────────────────────────────

(def write-capability-token
  "The dotted token the registry names for a schedule adapter's
  credential. Spelled here rather than at three literals."
  "schedule.write")

(def write-capability
  "The registry ROW for that token, as data — a deployment's boot seed
  ensures it beside the Gate powers (workqueue10.main's
  `ensure-capabilities!` is the shape), and nothing ever grants it.

  That is the whole point of the entry, and R-12.11 is explicit about
  it: the provider's token is held by the engine and never rides on a
  grant a sitter can wear. Naming the power is what makes it
  auditable — a reader of the registry can see that this house can
  write schedules into somebody else's scheduler, and where to look
  when it does.

  It is NOT a server's power, which is why this row stands where
  waymark-fp62.10.4 retired Gate's. No mcp_server row can hold this
  token: the adapters that enforce it are this engine's own, and a
  registry row is the only place the word can live."
  {:token write-capability-token
   :description
   (str "Write a seat's schedule into the harness's own scheduler — "
        "create, pause, resume and delete the provider's copy. Held by "
        "the engine, never granted: no sitter wears this.")
   :enforced_by
   (str "this engine's schedule adapters — the provider's token lives "
        "in the environment and never on a grant")})

;; ── the kind ────────────────────────────────────────────────────────

(def providers ["claude_routine" "jules" "cron"])

(g/defguard engine-writes-schedules
  {:reads [:principal]
   :hide true
   :explain "A schedule is the engine's record of how a seat wakes; the seat is what a person manages."}
  [_row _inp ctx]
  (if (= :system (get-in ctx [:principal :type]))
    (t/allow) (t/deny)))

(defn one-per-seat?
  "Does a schedule already stand for this seat? Reads through the ctx
  `:find` hook — the same transaction as the write — exactly as
  `roles/active-role?` does; a ctx without the hook declines with nil,
  so the render probe stays storage-free."
  [ctx seat-id]
  (when-some [find' (:find ctx)]
    (boolean (seq (find' :schedule {:seat (str seat-id)} {:limit 1})))))

(g/defguard one-schedule-per-seat
  {:judges [:seat]
   :reads [:schedule]
   :explain "That seat already has a schedule — one seat, one means of waking, or two copies fire the same seat twice."
   :open "The taken seats are the schedules collection, one query away; listing them in the form would enumerate every seat the house has ever opened."}
  [_row inp ctx]
  (if (and (some? (:seat inp)) (one-per-seat? ctx (:seat inp)))
    (t/deny {:vars {:seat (:seat inp)}})
    (t/allow)))

(g/defguard a-person-or-a-delegate
  {:reads [:principal]
   :explain "A link is a person's act. A person makes the Routine by hand, and a person — or a tool that person is signed in to — pastes its fire URL and its token here. An agent does not link a schedule."}
  [_row _inp ctx]
  ;; seats.clj's `a-person` posture, spelled again rather than
  ;; required: that guard's sentence is about opening an OFFICE, and
  ;; the refusal a caller reads here is about a credential. Copying
  ;; the three-line check also keeps seats.clj free to read this
  ;; namespace later, which a require in this direction would close.
  (let [{:keys [type acts-for]} (:principal ctx)]
    (if (or (= :human type)
            (and (= :agent type) (not (str/blank? (str acts-for)))))
      (t/allow)
      (t/deny))))

(defn fires-through
  "One line saying what ONE fire of this schedule goes out through
  (waymark ticket 360d207d), so a person sees a mislink before it
  fires: a runner pool, or none. `read'` is a render ctx's `:read`,
  (read' kind id); with none, the chair is not consulted. With the
  ctx's `:find` too, the pool is `pool-of`'s reading: the links whose
  `pools` name the row or its chair (S1b of docs/spec-seat.md §22)."
  [read' schedule-row & [ctx]]
  (let [chair (when read'
                (some->> (get-in schedule-row [:data :seat]) str not-empty
                         (read' :seat)
                         seats/chair-of
                         (read' :model)))
        find' (:find ctx)
        pool (when find'
               (seats/pool-from #(find' :runner_link {} {:limit 100000})
                                schedule-row chair))]
    (if pool
      (str "runner pool (" (count pool) " links)")
      "no link")))

(defn- fires-through-field
  "The schedule's :computed `fires_through`: `fires-through` over the
  reads the render ctx lends."
  [row ctx]
  (fires-through (:read ctx) row ctx))

(g/defguard fires-through-a-live-link
  {:reads [:seat :model :runner_link]
   :remedies [:runner_link/restate :runner_link/restore]
   :explain "Nothing this schedule fires through is live: no runner link in its pool, or in its model's, is. Restate or restore one of its links first."}
  [row _inp ctx]
  ;; `held?` read through the transaction at hand: the pool is
  ;; `pool-of`'s, and the row is held unless a link in it is live. The
  ;; runner link's pool is the only fire credential (ticket 9fa42626),
  ;; so a row with no pool has nothing to fire through.
  (if-some [read' (:read ctx)]
    (let [chair (some->> (get-in row [:data :seat]) str not-empty
                         (read' :seat)
                         seats/chair-of
                         (read' :model))
          find' (:find ctx)
          ;; a retired link stays in the pool here, so a pool whose
          ;; every link retired is held, not read as no pool (R-22.4)
          pool (when find'
                 (seats/pool-from #(map (fn [l] (dissoc l :state))
                                        (find' :runner_link {} {:limit 100000}))
                                  row chair))]
      (if (some #(= "live" (some-> (read' :runner_link %) :state name)) pool)
        (t/allow)
        (t/deny)))
    (t/allow)))                         ; probe ctx — decline to guess

(def ^:private engine-writes
  "The safety sentence every engine-written door here wears. The
  mirror's `sync-safety` is the precedent, and the reason is the
  same: these doors are bookkeeping, the row comes back by another
  door, and a `:reversible true` would promise a reverse a person
  could actually take — which is exactly what a hidden door is not."
  {:idempotent true :reversible false :confirm false
   :one-way "Bookkeeping the engine writes as it mirrors the seat out; the next push or read-back moves the row again."})

(defhandler record-break
  [row inp _ctx]
  ;; a broken row waits on a person, not on a time: a throttle's
  ;; `retry_after` would be stale the moment the row breaks
  (-> row
      (assoc-in [:data :note] (:note inp))
      (update :data dissoc :retry_after)))

(defhandler restate-model
  [row inp _ctx]
  (assoc-in row [:data :model] (:model inp)))

(declare waiting-texts waiting-note?)

(defhandler stamp-fire
  [row inp _ctx]
  ;; a fire that went out supersedes the throttle that held the last
  ;; one (waymark ticket e30762f3): the wake `hold-throttle` kept
  ;; pending would be one extra run out of `sweep-pending!`, so the
  ;; flag and the settle's due moment go the way of the note and
  ;; `retry_after` — the pair wakes/stamp-fired! clears after its own
  ;; fire, cleared here for the fires that never pass through it.
  ;;
  ;; Not so the fires that wait for a release grace (ticket c84f747a):
  ;; this fire named another row, or no row, and theirs are still owed.
  ;; Their texts stay, with the flag, the due moment and the note that
  ;; says they wait; `wakes/release!` clears the ones it sends.
  (let [waiting? (seq (waiting-texts (:data row)))]
    (-> row
        (assoc-in [:data :last_fired_at] (:last_fired_at inp))
        (cond-> (:last_run_url inp)
          (assoc-in [:data :last_run_url] (:last_run_url inp)))
        (cond-> (:last_runner inp)
          (assoc-in [:data :last_runner] (:last_runner inp)))
        (update :data dissoc :retry_after :wake_first_at :wake_textless)
        (update :data
                (fn [data]
                  (if waiting?
                    (cond-> data
                      (not (waiting-note? (:note data))) (dissoc :note))
                    (dissoc data :note :wake_pending :wake_due_at
                            :wake_text :wake_texts)))))))

(defhandler hold-throttle
  [row inp _ctx]
  ;; the wake that met the throttle waits for the time it named: the
  ;; wake loop sends it once `retry_after` has passed, and not before
  (-> row
      (assoc-in [:data :note] (:note inp))
      (assoc-in [:data :retry_after] (:retry_after inp))
      (assoc-in [:data :wake_pending] true)))

;; the wakes' pending marks (leases 5a, docs/spec-leases.md §4): what
;; was a maintenance write in wakes.clj is one door, so each mark is a
;; line in the log
(def wake-marks
  "The data keys the wakes' pending marks write, through `mark_wake`."
  [:wake_pending :wake_due_at :wake_first_at :wake_text :wake_texts
   :wake_textless :wake_fired_at :wake_heard :wake_seen :last_halted_wake
   :halted :note])

(def ^:private mark-wake-input
  [:map
   [:wake_pending {:optional true :x-display {:hidden true}} [:maybe :boolean]]
   [:wake_due_at {:optional true :x-display {:hidden true}} [:maybe :waymark/instant]]
   [:wake_first_at {:optional true :x-display {:hidden true}} [:maybe :waymark/instant]]
   [:wake_text {:optional true :x-display {:hidden true}} [:maybe :string]]
   [:wake_texts {:optional true :x-display {:hidden true}} [:maybe [:vector :string]]]
   [:wake_textless {:optional true :x-display {:hidden true}} [:maybe :boolean]]
   [:wake_fired_at {:optional true :x-display {:hidden true}} [:maybe :waymark/instant]]
   [:wake_heard {:optional true :x-display {:hidden true}} [:maybe [:vector :string]]]
   [:wake_seen {:optional true :x-display {:hidden true}} [:maybe [:vector :string]]]
   [:last_halted_wake {:optional true :x-display {:hidden true}} [:maybe :waymark/instant]]
   [:halted {:optional true :x-display {:hidden true}} [:maybe seats/halt-mark]]
   [:note {:optional true :x-display {:hidden true}} [:maybe [:string {:max 280}]]]])

(defhandler mark-wake
  [row inp _ctx]
  ;; a merge: a key given a value is written, a key given nil comes
  ;; off, and a key not given stays as the row holds it
  (update row :data
          (fn [data]
            (reduce (fn [d k]
                      (cond
                        (not (contains? inp k)) d
                        (nil? (get inp k)) (dissoc d k)
                        :else (assoc d k (get inp k))))
                    data wake-marks))))

;; the pool order alone: the links in the pool are each link's own
;; `pools`, written through the link's doors
(defhandler write-runner-order
  [row inp _ctx]
  (update row :data #(if-some [o (:runner_order inp)]
                       (assoc % :runner_order o)
                       (dissoc % :runner_order))))

(defresource schedule
  {:kind :schedule
   :plural "schedules"
   :states [:pending :live :held :paused :broken :ended]
   :initial :pending
   :terminal #{:ended}
   :nav :system
   ;; no seat leaves a live schedule: the engine pauses, breaks and
   ;; ends its copy as the seat moves
   :left-by [{:in #{:live} :by :engine :via :pause :to :paused}
             {:in #{:live} :by :engine :via :hold :to :held}
             {:in #{:live} :by :engine :via :fail :to :broken}
             {:in #{:live} :by :engine :via :end :to :ended}]
   :summary "{data.provider} · {state}"
   :label-template "{data.provider} schedule"
   :computed {:fires_through
              {:schema :string
               :x-display
               {:label "Fires through"
                :help "Which Routine one fire goes out on, worked out at read time: this row's own link, its model's link, a runner pool, or no link. A Routine id here that is not this seat's own is a mislink to fix before it fires."}
               ;; it reads the chair: with no :read it is absent,
               ;; never a false \"no link\" (ticket 82589f6e)
               :reads? true
               :fn fires-through-field}}
   ;; a sitter reads the seat it sits in (R-4.9); the schedule is the
   ;; engine's own record of how that seat wakes, and nothing on it is
   ;; a sitter's to read or write — so no own-surface here.
   :schema
   [:map
    [:seat {:kind :seat
            :x-display
            {:label "The seat this fires"
             :help "The seat whose sitting this schedule creates. One seat, one schedule: the engine writes this ref when the seat is opened."}}
     :waymark/ref]
    [:provider {:x-display
                {:label "Which scheduler holds the copy"
                 :help "The harness's own scheduler — the one that actually wakes the model on the cadence. The engine fires it through the link this row holds; it pushes no copy out and reads none back."
                 :choices {"claude_routine" "A Claude Routine — the built adapter."
                           "jules" "A scheduled Jules session — named, not yet built."
                           "cron" "An ordinary cron entry — named, not yet built."}}}
     (into [:enum] providers)]
    [:model {:optional true :kind :model
             :x-display
             {:label "The model the firing starts"
              :help "Defaults to the first of the seat's held-for list when the schedule is opened. Restate it to send a cheaper model for a day, or a substitute's."}}
     [:maybe :waymark/ref]]
    [:note {:optional true
            :x-display
            {:widget "prose"
             :label "What the provider last said"
             :help "The adapter's own sentence about why it could not reach the provider — a missing credential reads exactly as one. On a broken row a person must act; on a live row with `retry_after` it is a throttle, and nothing is needed from anyone. Cleared by the next successful push or fire. A fire that waits for a release grace says so here, and until when; that sentence is cleared when the fire goes out."}}
     [:maybe [:string {:max 280}]]]
    [:last_fired_at {:optional true
                     :x-display
                     {:label "Last fired"
                      :help "When the engine last started a run through the fire URL. Written by the engine, never by hand."}}
     [:maybe :waymark/instant]]
    [:last_run_url {:optional true
                    :x-display
                    {:raw true
                     :label "The last run"
                     :help "The provider's page for the run the last fire started. Written by the engine, never by hand."}}
     [:maybe [:string {:max 400}]]]
    ;; R-12.22's damper mark. Wave two writes it, by a maintenance
    ;; write and no transition; it is
    ;; declared here so the field exists the day the wake consumer
    ;; lands, and so a reader of this row can see why a match did not
    ;; fire.
    [:wake_pending {:optional true
                    :x-display
                    {:label "A wake is waiting"
                     :help "Set by the engine when a transition matched this seat's subscriptions inside the damper. The next fire after the damper lifts clears it."}}
     [:maybe :boolean]]
    ;; The wake consumer's OWN clock. `last_fired_at` is stamped only
    ;; after the provider answered, one consumer later; a burst of
    ;; matches inside one drain would each read it unstamped and fire.
    ;; So the wake stamps this the moment its fire goes out, and the
    ;; damper reads the later of the two.
    [:wake_fired_at {:optional true
                     :x-display
                     {:label "Last wake"
                      :help "When the engine last fired this seat for a matching transition. Engine-written."}}
     [:maybe :waymark/instant]]
    ;; The TRAILING edge's clock (waymark-fp62.17). A wake entry
    ;; that names `settle_seconds` does not fire on the match: the
    ;; match moves this moment forward, and the wake goes out after
    ;; it has passed. The maintenance write that sets `wake_pending`
    ;; sets this beside it, and the release clears the two together.
    [:wake_due_at {:optional true
                   :x-display
                   {:label "The wake is due"
                    :help "When the waiting wake may go out. The engine writes it when a transition matched a wake entry that settles, and a later match moves it forward. The wake goes out after this moment has passed. Engine-written."}}
     [:maybe :waymark/instant]]
    ;; The settle's cap (ticket 8f482592). Matches that never stop
    ;; would move `wake_due_at` forward for ever, so the first match of
    ;; a waiting settled wake is kept here, and the due moment is never
    ;; asked past it plus the entry's `max_wait_seconds`. Cleared with
    ;; the two marks above.
    [:wake_first_at {:optional true
                     :x-display
                     {:label "The wake's first match"
                      :help "When the first transition of the waiting wake matched a wake entry that settles. Later matches move the due moment forward, but not past this moment plus the entry's max_wait_seconds. Engine-written."}}
     [:maybe :waymark/instant]]
    ;; The words a deferred fire carries (ticket afb445d4). A fire whose
    ;; text names a row still in a closed sitting's release grace does
    ;; not go out: it waits as `wake_pending` until `wake_due_at`, and
    ;; the release fires it with this text rather than with none.
    [:wake_text {:optional true
                 :x-display
                 {:label "The waiting fire's text"
                  :help "The text of a fire that named a row still in its release grace. The fire waits until the grace lifts, and then goes out with this text. Engine-written."}}
     [:maybe :string]]
    ;; Every waiting fire's words (ticket 58573175). `wake_text` holds
    ;; one string, so a second fire deferred in the same grace took the
    ;; first's place. The list keeps each one time, in the order they
    ;; came, and the release sends one run for each; `wake_text` stays
    ;; as the first of them.
    [:wake_texts {:optional true
                  :x-display
                  {:label "The waiting fires' texts"
                   :help "The text of each fire that waits for a release grace, in the order they came. When the grace lifts, each goes out as its own run. Engine-written."}}
     [:maybe [:vector :string]]]
    ;; A wake with no text that waits beside those (ticket 58573175): a
    ;; damped wake-entry match walks the whole queue, so it goes out as
    ;; its own fire and not as one of the texts' one-row runs.
    [:wake_textless {:optional true
                     :x-display
                     {:label "A wake with no text waits too"
                      :help "Set by the engine when a wake that names no row waits beside a waiting fire's text. It goes out as its own run, which walks the whole queue. Engine-written."}}
     [:maybe :boolean]]
    ;; The replay's mark (waymark-fp62.21). The drain delivers at
    ;; least once, and a damped match carries no idempotency key, so
    ;; the wake remembers the last transitions it heard and a replay
    ;; of one of them sets nothing pending again.
    [:wake_heard {:optional true
                  :x-display
                  {:label "Transitions the wake heard"
                   :help "The last transitions that matched this seat's subscriptions, so a re-delivered one does not wake the seat twice. Engine-written."}}
     [:maybe [:vector :string]]]
    ;; The heads the wake last heard (ticket 70aca5e6). A self-loop on
    ;; a row whose `head_sha` has not moved since the wake last heard
    ;; it — the forge poll's `observe` on a red change — wakes
    ;; nothing, so a seat is not fired again and again for no news.
    [:wake_seen {:optional true
                 :x-display
                 {:label "Heads the wake heard"
                  :help "Each row's head when a transition on it last matched this seat's subscriptions, as kind/id and head. A self-loop on the same head does not wake the seat again. Engine-written."}}
     [:maybe [:vector :string]]]
    ;; The fuel wall's mark (waymark ticket b790752f). A wake that
    ;; matched while the seat's week of fuel was spent does not fire:
    ;; it waits as `wake_pending`, and this says when the wall last
    ;; held one back, so a person can see why the seat is quiet.
    [:last_halted_wake {:optional true
                        :x-display
                        {:label "A wake the budget held"
                         :help "When a matching transition last found this seat's week of fuel spent. The wake waits, and it goes out when the window rolls. Engine-written."}}
     [:maybe :waymark/instant]]
    ;; The wall itself (waymark ticket ae64b57c): what the last sit
    ;; past the wall answered — which wall, why, and when it lifts — so
    ;; the schedule says "halted: budget, lifts <date>" and not only
    ;; that a wake waits. A sit clear of the wall takes it off.
    [:halted {:optional true
              :x-display
              {:raw true
               :label "The wall the seat is against"
               :help "Which wall the seat's last sit found, the sentence that says why, and when it lifts. Cleared by the next sit clear of the wall. Engine-written."}}
     [:maybe seats/halt-mark]]
    ;; A throttle's mark (waymark ticket 48dc648c). The provider said
    ;; the Routine has no free run and named a time; the row stays
    ;; live, the wake stays pending, and nothing fires before this.
    [:retry_after {:optional true
                   :x-display
                   {:label "The Routine is free again at"
                    :help "The provider throttled the last fire and named this time. The schedule stays live and its waiting wake goes out once, after this moment; nothing is needed from a person. Cleared by the next fire that goes out. Engine-written."}}
     [:maybe :waymark/instant]]
    ;; the runner pool (waymark ticket d16b71bf): the order its links
    ;; are tried in, and the one the last run started through.
    [:runner_order {:optional true
                    :x-display
                    {:label "Pool order"
                     :help "How a fire picks among this schedule's runner links that may fire. Empty is least used."
                     :choices seats/runner-order-choices}}
     [:maybe (into [:enum] seats/runner-orders)]]
    [:last_runner {:optional true
                   :x-display
                   {:label "Last fired through"
                    :help "The runner link the last run started through. Engine-written."}}
     [:maybe [:string {:min 1 :max 200}]]]]
   :create-schema
   [:map
    [:seat {:kind :seat
            :x-display {:label "The seat this fires"
                        :help "The seat whose sitting this schedule creates."}}
     :waymark/ref]
    [:provider {:x-display
                {:label "Which scheduler holds the copy"
                 :help "The harness's own scheduler — the one that actually wakes the model on the cadence."
                 :choices {"claude_routine" "A Claude Routine — the built adapter."
                           "jules" "A scheduled Jules session — named, not yet built."
                           "cron" "An ordinary cron entry — named, not yet built."}}}
     (into [:enum] providers)]
    [:model {:optional true :kind :model
             :x-display {:label "The model the firing starts"
                         :help "The first of the seat's held-for list, unless a person restates it."}}
     [:maybe :waymark/ref]]]
   :filterable {:state #{:eq :in}
                :seat #{:eq}
                :provider #{:eq :in}}
   :sortable {:fields [:created_at :updated_at] :default "-created_at"}
   :create-guards [engine-writes-schedules one-schedule-per-seat]
   :actions
   {:pause
    {:from #{:live} :to :paused
     :guards [engine-writes-schedules]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The copy stops firing and keeps its settings; unparking the seat resumes it."}
     :display {:label "Copy paused"}}

    :resume
    {:from #{:paused} :to :live
     :guards [engine-writes-schedules]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The copy fires again on its cadence; parking the seat pauses it."}
     :display {:label "Copy resumed"}}

    ;; the provider's own pause (ticket f1918540): a 400 says the
    ;; Routine is paused at the provider. That is not a person's or a
    ;; seat's park, so it has a state of its own, and the next fire the
    ;; provider answers with anything but 400 mends it to live;
    ;; `resume` stays the one way out of `paused`.
    :hold
    {:from #{:pending :live} :to :held
     :guards [engine-writes-schedules]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The row says the provider paused the Routine; the next fire the provider answers mends it."}
     :display {:label "The provider paused the Routine"}}

    :fail
    {:from #{:pending :live :held :paused :broken} :to :broken
     :input [:map
             [:note {:x-display {:hidden true}} [:string {:min 1 :max 280}]]]
     :record true
     :guards [engine-writes-schedules]
     :edit {:prefill [:note] :fence false
            :unfenced-reason
            "The adapter's own sentence, written where the throw happened; no human read preceded it to fence against."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The row says what the adapter could not do; the next push that succeeds clears it."}
     :handler record-break
     :display {:label "Adapter could not reach the provider" :style :danger}}

    :end
    {:from #{:pending :live :held :paused :broken} :to :ended
     :guards [engine-writes-schedules]
     :safety {:idempotent true :reversible false :confirm false
              :final "The seat closed and its copy is deleted at the provider; opening a seat again opens a new schedule."}
     :display {:label "Copy deleted" :style :danger}}

    ;; the ONE human door (R-12.1): a person restates the model for a
    ;; substitute day. Everything else on this row is the engine's.
    :restate
    {:from #{:live} :to :live
     :input [:map
             [:model {:optional true :kind :model
                      :x-display
                      {:label "The model the firing starts"
                       :help "One of the models the seat is held for. The push that follows writes it onto the provider's copy; the prompt does not change."}}
              [:maybe :waymark/ref]]]
     :record true
     ;; :ref-unjudged — any active model may be named here; which models
     ;; are active is the model kind's own state rule, not this door's
     :waives #{:ref-unjudged}
     :edit {:prefill [:model] :fence true}
     :safety {:idempotent true :reversible false :confirm false}
     :handler restate-model
     :display {:label "Restate the model" :order 1}}

    ;; the pool order (waymark ticket 529deb73): a person's hand only.
    ;; Which links are in the pool is each link's own `pools`, through
    ;; its join_pool and leave_pool; the engine never sets an order.
    :set_runner_order
    {:from #{:pending :live :held :paused :broken} :to :same
     :input [:map
             [:runner_order {:optional true
                             :x-display
                             {:label "Pool order"
                              :help "How a fire picks among the links that may fire. Empty is least used."
                              :choices seats/runner-order-choices}}
              [:maybe (into [:enum] seats/runner-orders)]]]
     :writes #{:runner_order}
     :record true
     :guards [a-person-or-a-delegate]
     :edit {:prefill [:runner_order] :fence false
            :unfenced-reason
            "The order is restated whole; a restate replaces what stands rather than editing it."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The new order replaces the one this row held; another restate puts it back."}
     :handler write-runner-order
     :display {:label "Pool order" :order 4
               :description "How a fire picks among the links in this schedule's pool. A runner link joins the pool from its own page, through Add to a pool"}}

    ;; a new link fires on the next fire (R-22.4 of docs/spec-seat.md):
    ;; hidden, the engine's own hand, and only while what the row
    ;; fires through is live. The state is all it writes. It is the ONE
    ;; door into `live` from `pending`, `broken` or `held` (tickets
    ;; 5e0d33bb, f1918540): the consumer takes it after each
    ;; bookkeeping door, and never on a paused row.
    :mend
    {:from #{:broken :pending :held} :to :live
     :guards [engine-writes-schedules fires-through-a-live-link]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The row fires again through its pool; the next refusal breaks it again."}
     :display {:hidden true :label "Mended by a live link"}}

    ;; the fire's landing (R-12.19). Hidden, engine-written, and the
    ;; one door that clears a 429's note: the next fire that goes out
    ;; says the Routine has a free run again.
    :fired
    {:from #{:pending :live :held :paused :broken} :to :same
     :input [:map
             [:last_fired_at {:x-display {:hidden true}} :waymark/instant]
             [:last_run_url {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 400}]]]
             [:last_runner {:optional true :x-display {:hidden true}}
              [:maybe [:string {:min 1 :max 200}]]]]
     :record true
     :guards [engine-writes-schedules]
     :edit {:prefill [:last_fired_at] :fence false
            :unfenced-reason
            "Stamped by the fire consumer the moment the provider answered; no read preceded it to fence against."}
     :safety engine-writes
     :handler stamp-fire
     :display {:label "Routine fired"}}

    ;; the provider's throttle (waymark ticket 48dc648c): a 429, or any
    ;; answer naming a retry time, is not a bad link. The row stays
    ;; where it stands, says so, and keeps its wake pending until
    ;; `retry_after`. A paused or held row is fired too, so a throttle
    ;; must land on it as well; a paused row stays paused, and a
    ;; pending, broken or held row goes live through `mend`, as it does
    ;; after `fired`.
    :throttle
    {:from #{:pending :live :held :paused :broken} :to :same
     :input [:map
             [:note {:x-display {:hidden true}} [:string {:min 1 :max 280}]]
             [:retry_after {:x-display {:hidden true}} :waymark/instant]]
     :record true
     :guards [engine-writes-schedules]
     :edit {:prefill [:note :retry_after] :fence false
            :unfenced-reason
            "Written by the fire consumer the moment the provider throttled the fire; no read preceded it to fence against."}
     :safety engine-writes
     :handler hold-throttle
     :display {:label "Routine throttled"}}

    ;; the wakes' pending marks (leases 5a): hidden, engine-written,
    ;; and one door that stays in each state a wake may meet
    :mark_wake
    {:from #{:live :held :paused :pending :broken} :to :same
     :input mark-wake-input
     :writes (set wake-marks)
     :guards [engine-writes-schedules]
     :record true
     :waives #{:edit-shape}
     :safety engine-writes
     :handler mark-wake
     :display {:hidden true :label "Wake marked"}}}
   :deviations
   ["The schedule is NOT declared through server/mirror, though R-12.0 names the calendar as the precedent. Three reasons: mirror's authority points inward (a pull wins; R-12.3 wants a read-back that reports and never repairs), mirror refuses a kind that declares its own :states (R-12.1 names four), and MirrorAdapter has no pause, resume or delete (calendar10 had to hang delete-event! off the side of the protocol). The seam is ScheduleAdapter instead, and the bookkeeping posture — hidden system doors over ordinary data fields — is borrowed from mirror whole."
    "R-12.1 lists four states; this kind has five. `ended` is where a retired or merged seat's schedule lands once the copy is deleted. The alternative was returning the row to `pending`, which means \"no copy yet\" and invites the next push to make one."
    "R-12.1's field table does not list `note`, but its sentence for the `broken` state says \"the note says why\" and R-12.11 asks the kind to serve a broken provider \"with a note saying so\". So `note` is a field, engine-written, and the `fail` door records it in the log as well (the log's inputs column, R-4.7's spelling)."
    "R-12.2 calls the push a post-commit effect at the wire boundary. It is a durable log consumer here, which is what the brief asked to be built and the more honest of the two under a crash: the log is the record, and an effect that dies takes its push with it. Wave two may move it (waymark-442.14)."
    "R-12.6's ceiling is not pushed to the provider. The Routines API is not pinned in this repository, and R-12.6 names the fallback itself — the walk's `rows_per_firing` caps the firing. One field on the create payload when the real API is known."
    "R-12.18 asks `link` to record. It does NOT record here, and the seat's `offer_key` made the same trade for the same reason: a recorded action persists its raw inputs into the transition log, and this input is the token — which R-12.11 says is never in a transition's recorded inputs. The transition row still says a link was made, by whose hand and when."
    "R-12.20 writes the 429 as a refusal sentence. It lands here as a NOTE on a LIVE row instead, through `throttle`. The fire goes out after the commit, so the provider's answer arrives when the door is already closed and there is nobody left to refuse; the row says what the provider said, keeps its wake pending with the `retry_after` the provider named, and the wake loop sends that wake once the time has passed. Only an answer that says the link itself is bad (401, 403, 404) breaks the row."
    "`fired` accepts a `paused` and a `pending` row as well as `live` and `broken`, where R-12.18 names two states, and keeps each where it stands (ticket 5e0d33bb): a bookkeeping door is not a move. A pending or broken row the provider answered goes live through `mend`."
    "R-12.1 has no state for a Routine the provider paused; this kind names it `held` (ticket f1918540). A 400 moves a live or pending row to `held`, not to `paused`: `paused` is a person's or a seat's park and only `resume` leaves it, while `held` is the provider's, and the next fire the provider answers with anything but 400 mends it to live."]})

;; ── reading the seat and the model ──────────────────────────────────

(defn- raw-row [eng kind id]
  (when (and id (contains? (inv/resources eng) kind))
    (store/with-tx (:storage eng)
      (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))))

(defn- rows-where [eng kind where limit]
  (if (contains? (inv/resources eng) kind)
    (store/with-tx (:storage eng)
      (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit limit})))
    []))

(defn schedule-for-seat
  "The schedule row standing for this seat, or nil. One indexed read on
  the promoted `seat` column."
  [eng seat-id]
  (first (rows-where eng :schedule {:seat (str seat-id)} 1)))

(defn- clip [s]
  (let [s (str s)]
    (if (<= (count s) 280) s (str (subs s 0 277) "..."))))

;; ── the fire seam (R-12.18 to R-12.20) ──────────────────────────────
;;
;; ONE operation, because the API has one endpoint. Unlike the six
;; above, this one is PINNED: the URL, the three headers, the body and
;; the answer are the facts recorded on waymark-fp62.7.3, not
;; assumptions. The credential is not the engine's either — it is the
;; runner link's `fire_token`, which a person pasted there — so the
;; adapter holds no token and reads no environment.

(defprotocol FireAdapter
  "The Routine's fire endpoint, as one operation."
  (fire-routine [a fire-url token text]
    "Start one run of the Routine at `fire-url`, bearing `token`, with
    `text` in the run's fire payload (nil sends no text at all) →
    {:session-id :session-url}.

    A non-2xx throws ex-info carrying `:status`, and `:retry-after`
    where the provider sent that header. The caller lands the throw on
    the schedule row as a state and a note; nothing is retried here."))

(def routine-fire-beta
  "The beta header the fire endpoint demands. Without it the provider
  answers 400, which is the same answer it gives for a paused Routine."
  "experimental-cc-routine-2026-04-01")

(def routine-api-version
  "The API version header, the provider's own long-standing value."
  "2023-06-01")

(defn- retry-after-of [^HttpResponse resp]
  (some-> (.headers resp) (.firstValue "retry-after") (.orElse nil) str not-empty))

(defrecord RoutineFire [^HttpClient client]
  FireAdapter
  (fire-routine [_ fire-url token text]
    ;; PINNED: POST {fire-url}
    ;;   headers authorization: Bearer <token>,
    ;;           anthropic-beta, anthropic-version, content-type
    ;;   body    {"text": …} when there is text, else {}
    ;;   ← 200 {"type": "routine_fire",
    ;;          "claude_code_session_id": "session_01…",
    ;;          "claude_code_session_url": "https://…"}
    (let [body (wire/write-json (if-some [t (some-> text str not-empty)]
                                  {:text t}
                                  {}))
          req (-> (HttpRequest/newBuilder (URI/create (str fire-url)))
                  (.timeout (Duration/ofSeconds 20))
                  (.header "authorization" (str "Bearer " token))
                  (.header "anthropic-beta" routine-fire-beta)
                  (.header "anthropic-version" routine-api-version)
                  (.header "content-type" "application/json")
                  (.POST (HttpRequest$BodyPublishers/ofString
                          body StandardCharsets/UTF_8))
                  (.build))
          resp (.send client req (HttpResponse$BodyHandlers/ofString))
          status (.statusCode resp)]
      ;; the status is judged BEFORE the body is parsed — routines-call!
      ;; carries the same recorded lesson, and a 429 behind a proxy
      ;; that answers plain text is exactly the case that taught it
      (when (>= status 400)
        (throw (ex-info (str "the routines api answered " status
                             " for the fire")
                        (cond-> {:status status :body (.body resp)}
                          (retry-after-of resp)
                          (assoc :retry-after (retry-after-of resp))))))
      (let [parsed (try (some-> (.body resp) not-empty wire/read-json)
                        (catch Exception _ nil))]
        {:session-id (some-> (:claude_code_session_id parsed) str not-empty)
         :session-url (some-> (:claude_code_session_url parsed) str not-empty)}))))

(defn routine-fire
  "The real boundary over the fire endpoint. It takes no configuration:
  every fire carries its own URL and its own token off the row."
  []
  (->RoutineFire
   (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) (.build))))

(def ^:private default-fire-adapter
  "One client for the process, built on first use — the fire path runs
  inside a consumer's own thread, and a client per fire would be a
  connection pool per wake."
  (delay (routine-fire)))

(defn fire-adapter-of
  "The adapter this engine fires through. The engine opt wins (the
  tests' seam, and offline dev's); everything else gets the real one."
  [eng]
  (or (:fire-adapter eng) @default-fire-adapter))

;; ── writing the schedule onto the seat ──────────────────────────────

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 schedules: " parts))))

(defn stamp-seat!
  "Write `schedule` onto the seat's document (R-12.1: engine-written).

  It is a MAINTENANCE write — `store/update-data!`, the door the
  belief refold and the job's progress already use: the document and
  the clock index only, no version bump, no transition. The engine
  recording which row it just minted for a seat is not a move in the
  seat's story, and a `create` immediately followed by a system
  `restate` nobody made would read as one.

  A house whose `seat` kind is not registered skips the write and says
  so on *err* — the schedule still stands, and the ref lands the next
  time a seat is created on an engine that serves the kind."
  [eng seat-id schedule-id]
  (if (contains? (inv/resources eng) :seat)
    (store/with-tx (:storage eng)
      (fn [tx]
        (let [st (:storage eng)]
          (when-some [row (store/load-row st tx :seat (str seat-id) {})]
            (store/maintain! :cache
             st tx :seat (:id row)
             ;; a ref is a string on the wire and in the document, so
             ;; the stored map takes it as it stands — no encode pass
             ;; is owed for one scalar
             (assoc (:data row) :schedule (str schedule-id))
             (:next-flip-at row))
            true))))
    (do (warn! "no seat kind on this engine — schedule " schedule-id
               " is not stamped onto seat " seat-id)
        false)))

;; ── minting the schedule row ────────────────────────────────────────

(def default-provider
  "The provider a seat's schedule is opened with. One adapter is built,
  and a house that wants another restates nothing — it changes this,
  which is the honest shape until a second adapter exists."
  "claude_routine")

(defn ensure-schedule!
  "The schedule row for this seat, minted if absent (R-12.2). Returns
  the row.

  Idempotent by lookup rather than by guard refusal: the consumer is
  at-least-once and a replayed seat `create` must be a no-op, not a
  409 that parks the drain. `one-schedule-per-seat` is still the law
  at the door — it is what refuses a second schedule written by any
  other hand.

  The model defaults to the FIRST of the seat's `held_for` (R-12.1);
  an empty list means the seat takes any model, and the schedule then
  names none and the provider uses its own."
  [eng seat-row]
  (or (schedule-for-seat eng (:id seat-row))
      (let [held (get-in seat-row [:data :held_for])
            model (some-> (first held) str not-empty)
            born (:row (inv/create! eng :schedule
                                    (cond-> {:seat (str (:id seat-row))
                                             :provider default-provider}
                                      model (assoc :model model))
                                    {:principal system-actor}))]
        (stamp-seat! eng (:id seat-row) (:id born))
        ;; read it back rather than handing on the create's decoded row:
        ;; every other function here reads STORED rows, and one shape
        ;; through the whole namespace is worth one indexed read
        (or (schedule-for-seat eng (:id seat-row)) born))))

;; ── the push ────────────────────────────────────────────────────────

(defn- now [eng] ((:now-fn eng)))

(defn- act! [eng id action body]
  (:row (inv/invoke! eng :schedule (str id) action body
                     {:principal system-actor})))

(defn- try-act!
  "`act!`, with a 409 or a missing row swallowed and said on *err*.
  The fire path uses it because the provider's answer arrives long
  after the door closed: the row may have moved under it, and a throw
  inside a consumer parks the drain for every other seat."
  [eng row action body]
  (try
    (let [out (act! eng (:id row) action body)]
      ;; a fire's landing keeps the row where it stands (ticket
      ;; 5e0d33bb), so a pending, broken or held row the provider
      ;; answered goes live through `mend` here, before anyone reads it
      (when (and (contains? #{:fired :throttle} action)
                 (contains? #{"pending" "broken" "held"} (some-> (:state row) name)))
        (try-act! eng row :mend nil))
      out)
    (catch Exception e
      (warn! "schedule " (:id row) " could not record " action " — "
             (ex-message e))
      nil)))

(defn- note!
  "Write one sentence onto the row through `fail`, unless the row
  already says exactly that. Idempotent by intent: a provider that
  refuses every minute writes one broken row, not a log of them."
  [eng row sentence]
  (let [note (clip sentence)]
    (when (not= note (get-in row [:data :note]))
      (act! eng (:id row) :fail {:note note}))
    nil))

(defn- break! [eng row ^Exception e]
  (note! eng row (or (not-empty (str (ex-message e)))
                     "The adapter could not reach the provider.")))

(declare provider-note land-answer!)

(def ^:private link-refusals
  "The statuses that say the link itself is bad. Only these break a
  row; a throttle never does."
  #{401 403 404})

(defn- retry-seconds
  "The seconds a provider's answer names before the next try: the
  Retry-After header's delta-seconds, or a number the body names
  after 'try again after' or 'retry after'. nil when neither does."
  [retry-after body]
  (or (some->> retry-after str clojure.string/trim (re-matches #"\d{1,9}")
               Long/parseLong)
      (some->> body str
               (re-find #"(?i)(?:try again|retry) after\D{0,3}(\d{1,9})")
               second
               Long/parseLong)))

(defn- retry-date
  "A Retry-After header spelled as an HTTP-date, as an instant."
  [retry-after]
  (try (some-> retry-after str clojure.string/trim not-empty
               (java.time.ZonedDateTime/parse
                java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME)
               .toInstant)
       (catch Exception _ nil)))

(defn- throttle?
  "Is this refusal a throttle — a 429, or any answer that names a retry
  time — and not a bad link?"
  [status retry-after body]
  (let [status (some-> status long)]
    (and (not (contains? link-refusals status))
         (not= 400 status)
         (boolean (or (= 429 status)
                      (some-> retry-after str not-empty)
                      (retry-seconds nil body)
                      (some->> body str (re-find #"(?i)no free run")))))))

(defn retry-instant
  "The instant a throttle names, read from `from`: the Retry-After
  header's seconds or a number the body names, else the header's
  HTTP-date. A throttle that names no time waits a minute."
  ^Instant [^Instant from retry-after body]
  (or (some->> (retry-seconds retry-after body) (.plusSeconds from))
      (retry-date retry-after)
      (.plusSeconds from 60)))

(defn- throttle!
  "Keep the row live through `throttle`, saying the provider's sentence
  and the instant it named. The wake stays pending, and the wake loop
  sends it once, at or after that instant. A throttle that names no
  time waits a minute. `said`, when given, is the sentence instead —
  a transient refusal that is not a 429 says its own."
  ([eng row retry-after body] (throttle! eng row retry-after body nil))
  ([eng row retry-after body said]
  (let [from (or (instant-of (now eng)) (Instant/now))
        secs (retry-seconds retry-after body)
        until (retry-instant from retry-after body)
        sentence (or (some-> said str not-empty)
                     (provider-note 429 (or (some-> retry-after str not-empty)
                                            (some-> secs str)))
                     "The Routine has no free run.")]
    (try-act! eng row :throttle {:note (clip sentence)
                                 :retry_after (str until)})
    nil)))

(declare pool-of)

(defn fires-out?
  "Has ONE fire of this schedule a way out (waymark ticket 102d00d2):
  a runner pool, the row's own or its chair's — the only fire
  credential (ticket 9fa42626). The seat's `fire` door judges whether a
  runner of the pool is live."
  [eng schedule-row]
  (some? (pool-of eng schedule-row
                  (raw-row eng :seat (get-in schedule-row [:data :seat])))))

;; ── the fire (R-12.19, R-12.20) ─────────────────────────────────────

(defn waiting-texts
  "The texts of the fires this row's data keeps waiting, in the order
  they came (ticket 58573175): `wake_texts`, or the one `wake_text` of
  a row written before the list. → a vector, empty when none waits."
  [data]
  (or (some->> (:wake_texts data) (keep #(some-> % str not-empty)) seq vec)
      (some-> (:wake_text data) str not-empty vector)
      []))

(defn keep-waiting
  "`data` with one more waiting text (ticket 58573175): added to
  `wake_texts` unless it already waits there, with `wake_text` the
  first of them. A wake already pending with NO text is marked
  `wake_textless`, so the release still sends it as its own fire and
  the text does not turn its queue walk into a one-row run."
  [data text]
  (let [held (waiting-texts data)
        texts (if (some #{text} held) held (conj held text))]
    (cond-> (assoc data
                   :wake_pending true
                   :wake_texts texts
                   :wake_text (first texts))
      (and (:wake_pending data) (empty? held))
      (assoc :wake_textless true))))

(defn keep-textless
  "`data` with a wake of no text remembered beside its waiting texts,
  when it has any (ticket 58573175). With none waiting, `wake_pending`
  alone already says it, and `data` is answered as it is."
  [data]
  (cond-> data
    (seq (waiting-texts data)) (assoc :wake_textless true)))

(def ^:private waiting-note-opening
  "How every note `defer-fire!` writes begins, so `clear-wake` can tell
  it from a sentence of the provider's."
  "Waiting for ")

(defn- waiting-note? [note]
  (.startsWith (str note) (str waiting-note-opening)))

(defn- waiting-note
  "The sentence a row with `n` deferred fires says while they wait."
  [n ^Instant due]
  (clip (if (= 1 n)
          (str waiting-note-opening "the release grace of the row this fire"
               " names; fires at " due ".")
          (str waiting-note-opening "the release grace of the rows " n
               " fires name; they fire at " due "."))))

(defn clear-wake
  "`data` with nothing waiting: the pending flag, its due moment, the
  waiting texts, and the waiting note `defer-fire!` wrote. A note the
  provider wrote stays."
  [data]
  (cond-> (dissoc data :wake_pending :wake_due_at :wake_first_at :wake_text
                  :wake_texts :wake_textless)
    (waiting-note? (:note data)) (dissoc :note)))

(defn mark-wake!
  "Write the wake marks `marks` names through the schedule's `mark_wake`
  door as the engine (leases 5b, docs/spec-leases.md §4): one line in
  the log. A key given nil comes off; a key not given stays. → true when
  the door wrote, nil when it refused (a row that has ended, or none)."
  [eng schedule-id marks]
  (try (inv/invoke! eng :schedule (str schedule-id) :mark_wake marks
                    {:principal system-actor})
       true
       (catch Exception _ nil)))

(def ^:private deferral-marks
  "The marks a deferral writes: the pending flag, its due moment, the
  waiting texts and the waiting note."
  [:wake_pending :wake_due_at :wake_text :wake_texts :wake_textless :note])

(defn- defer-fire!
  "Keep a fire that must wait for a release grace (ticket afb445d4) the
  way a deferred wake is kept: `wake_pending`, `wake_due_at` at the
  lift — forward only, as `wakes/due-at` holds it — and the fire's text
  among the waiting texts (`keep-waiting`), so `wakes/release!` sends
  it then with its words. The row's `note` says that a fire waits and
  until when (ticket 58573175), unless the provider's own sentence
  stands there. One `mark_wake` (leases 5b)."
  [eng schedule-row ^Instant lift text]
  (let [held (instant-of (get-in schedule-row [:data :wake_due_at]))
        due (if (and held (.isAfter ^Instant held lift)) held lift)
        data (keep-waiting (:data schedule-row) text)
        note (get-in schedule-row [:data :note])]
    (mark-wake! eng (:id schedule-row)
                (select-keys
                 (cond-> (assoc data :wake_due_at (str due))
                   (or (nil? note) (waiting-note? note))
                   (assoc :note (waiting-note
                                 (count (waiting-texts data)) due)))
                 deferral-marks)))
  nil)

(defn defer-until-elsewhere-lifts!
  "A sit that walked nothing because an open sitting of ANOTHER seat
  holds its rows (ticket bf17ce48): the seat's schedule is marked
  pending, due when the first of `ids` that such a hold keeps is let go
  (`seats/elsewhere-lifts-at`, forward only, as `defer-fire!` holds
  it), so the tick fires the seat again then. A run fired by hand, or a
  wake that raced the claim, is otherwise not woken once the hold ends.
  `text` joins the waiting texts (`keep-waiting`); nil keeps a wake of
  no text, so the release walks the queue. One `mark_wake` (leases 5b).
  → the due Instant when it was written, else nil."
  [eng seat-row ids text]
  (when-some [schedule-row (when seat-row
                             (schedule-for-seat eng (:id seat-row)))]
    (when-some [lift (seats/elsewhere-lifts-at eng seat-row ids
                                               ((:now-fn eng)))]
      (let [held (instant-of (get-in schedule-row [:data :wake_due_at]))
            due (if (and held (.isAfter ^Instant held ^Instant lift)) held lift)
            data (if text
                   (keep-waiting (:data schedule-row) text)
                   (assoc (keep-textless (:data schedule-row))
                          :wake_pending true))]
        (when (mark-wake! eng (:id schedule-row)
                          (select-keys (assoc data :wake_due_at (str due))
                                       deferral-marks))
          due)))))

(defn already-fired?
  "Has this row already been fired FOR this transition? `last_fired_at`
  is stamped after the provider answered, so a stamp at or after the
  transition's own instant means the drain is replaying one it already
  carried out. That is the whole dedupe: at-least-once delivery must
  not start a second run of somebody's Routine."
  [schedule-row at]
  (let [stamped (instant-of (get-in schedule-row [:data :last_fired_at]))
        at (instant-of at)]
    (boolean (and stamped at
                  (not (.isBefore ^Instant stamped ^Instant at))))))

(defn provider-note
  "The provider's answer, as the one sentence the row carries
  (R-12.20). nil for a status this engine has no sentence for — the
  exception's own message is the note then."
  [status retry-after]
  (case (some-> status long)
    429 (str "The Routine has no free run. Try again after "
             (or (some-> retry-after str not-empty) "a minute") ".")
    400 "The Routine is paused at the provider."
    401 "The Routine refused the token."
    404 "No Routine answers the fire URL."
    nil))

;; ── the provider fire interface (runner links 1b) ───────────────────
;;
;; One question every provider answers the same way, whatever its wire:
;; start a run through this link, with this text. The answer is exactly
;; one of four — started, throttled, transient, or the link is bad — so
;; a caller (a schedule, a runner link) lands it without knowing the
;; provider.

(defprotocol Provider
  "One provider's fire, answered in one of four words."
  (fire [p link text]
    "Start one run through `link` ({:fire_url :fire_token}) with `text`
    → exactly one of {:started run-url}, {:throttled retry-after},
    {:transient reason} or {:bad-link reason}; `run-url` and
    `retry-after` may be nil. `bad-link` is the link itself refused (a
    401, 403 or 404); `transient` is anything else that started no run
    (a 5xx, an unreachable provider, another 4xx), and leaves the link
    live and the fire unspent. The
    answer may carry more beside its one word (`:session-id`, `:body`,
    `:status`) for a caller that says more. Nothing throws."))

(defrecord ClaudeRoutine [adapter]
  Provider
  (fire [_ link text]
    (try
      (let [answer (fire-routine adapter
                                 (some-> (:fire_url link) str not-empty)
                                 (some-> (:fire_token link) str not-empty)
                                 text)]
        {:started (some-> (:session-url answer) str not-empty)
         :session-id (:session-id answer)})
      (catch Exception e
        (let [{:keys [status retry-after body]} (ex-data e)
              reason (or (provider-note status retry-after)
                         (not-empty (str (ex-message e)))
                         "The adapter could not reach the provider.")]
          (cond
            (throttle? status retry-after body)
            {:throttled retry-after :body body}

            (contains? link-refusals (some-> status long))
            {:bad-link reason :status status}

            :else {:transient reason :status status}))))))

(defn claude-routine
  "The claude_routine provider, firing through `adapter` — the real
  RoutineFire, or a test's fake."
  [adapter]
  (->ClaudeRoutine adapter))

(def fire-payload-tag
  "The block a seat's instructions name, spelled here so the engine's
  text and a person's instructions cannot drift apart."
  "routine-fire-payload")

(defn fire-text
  "The text ONE fire carries (R-3 of waymark-fp62.7.23, R-12.37): the
  seat's instructions, the line
  that names the seat, the line that carries this firing's own key,
  and the person's prose inside the block the
  instructions name.

      <the instructions>

      Seat: <id> (<name>).
      Key: <the key of this firing>

      <routine-fire-payload>
      <the prose>
      </routine-fire-payload>

  A seat that wrote no instructions fires the prose as it always did,
  nil and all — a textless fire is a run that walks the queue. PURE,
  and nothing here is ever cut: the instructions are the seat's own
  words and the prose is a person's, and a fire that quietly dropped
  half of either would be a run doing something nobody asked for.

  THE KEY SITS UNDER THE SEAT LINE, and the two are one paragraph
  because the sit takes both together: the session reads the seat it
  is to name and the key it is to present, side by side, at the head
  of the text. The key is the engine's own (`seats/hold-fire-key!`)
  and the caller hands it in. Nil leaves the line out, which is the
  firing of a seat whose Routine holds a standing key of its own.

  The payload block is there only when there is prose, so a cadence
  wake reads the instructions, the seat line and the key, and stops.
  The transition log keeps the prose alone, as it always did: this is
  composed after the door closed, the record is what a person wrote,
  and a key is in no record at all (R-12.11)."
  ([seat-row prose] (fire-text seat-row prose nil))
  ([seat-row prose key]
   (let [prose (some-> prose str not-empty)
         key (some-> key str not-empty)]
     (if-some [instructions (some-> (get-in seat-row [:data :instructions])
                                    str not-empty)]
       (str/join "\n\n"
                 (remove nil?
                         [instructions
                          (str "Seat: " (:id seat-row)
                               " (" (get-in seat-row [:data :name]) ")."
                               (when key (str "\nKey: " key)))
                          (when prose
                            (str "<" fire-payload-tag ">\n" prose
                                 "\n</" fire-payload-tag ">"))]))
       prose))))

(defn land-answer!
  "Land one provider `answer` (the Provider's four words) on
  `schedule-row`. `stamp` is merged into a started run's `fired` (a
  pull link's `last_runner`); a pull link's answer lands here from
  `land-pull-answer!`.

  2xx stamps `last_fired_at` and `last_run_url` through `fired`, which
  also clears the note and `retry_after`. A 429, or any answer that
  names a retry time, is a throttle: the row stays live through
  `throttle`, records `retry_after`, and keeps its wake pending for the
  wake loop to send after that time. A 400 is the provider saying the
  Routine is paused: a live or pending row is held, a held or paused
  row stays as it is, and a broken row says the sentence and tries
  again. Only
  a link refusal — a 401, a 403 or a 404, the last also an unknown
  Routine — lands as a note on a broken row. Anything else (a 5xx, an
  unreachable provider, another 4xx) is transient: the row stays live
  with the provider's sentence and tries again in a minute.

  Nothing here re-throws and nothing here retries. A throwing consumer
  parks its cursor, and a retry inside a drain is a second run of a
  Routine nobody asked for.

  `at` is the FIRE TRANSITION's own instant, and it is what gets
  stamped — not this machine's clock. The stamp is what `already-fired?`
  compares a replay against, so the two must be read off one clock;
  the log's is the one both the engine and the database agree on."
  [eng schedule-row answer at & [stamp]]
  (let [status (some-> (:status answer) long)]
    (cond
      (contains? answer :started)
      (try-act! eng schedule-row :fired
                (cond-> (merge {:last_fired_at (str (or (instant-of at) (now eng)))}
                               stamp)
                  (:started answer)
                  (assoc :last_run_url (str (:started answer)))))

      ;; a throttle: the row stays live and the wake waits
      (contains? answer :throttled)
      (throttle! eng schedule-row (:throttled answer) (:body answer))

      ;; a localfire that cannot say whether the run started: the fire
      ;; is closed, nothing is paused or throttled, and the next cadence
      ;; wake fires as usual (ticket 734b3ecd)
      (contains? answer :not-started)
      nil

      ;; a paused Routine is not a bad link, nor a seat's park: a live
      ;; or pending row says so as `held` (ticket f1918540), a held or
      ;; paused row already stands still, and a broken row keeps trying
      (= 400 status)
      (case (some-> (:state schedule-row) name keyword)
        (:live :pending) (try-act! eng schedule-row :hold nil)
        (:held :paused) nil
        (throttle! eng schedule-row nil nil (:transient answer)))

      ;; only the link itself breaks the row
      (contains? link-refusals status)
      (note! eng schedule-row (:bad-link answer))

      ;; a transient refusal: the row stays live and tries again
      :else (throttle! eng schedule-row nil nil (:transient answer)))))

(defn land-pull-answer!
  "What a localfire answered for a fire it took through the pull link
  `runner`, landed on the schedule `id` exactly as `land-answer!` lands
  a push answer, with `runner` as `last_runner`. nil when no such schedule."
  [eng id answer at runner]
  (when-some [row (raw-row eng :schedule id)]
    (land-answer! eng (update row :state #(some-> % name keyword))
                  answer at {:last_runner (str runner)})))

(defn miss!
  "A pull fire for the schedule `id` that no localfire took within a
  day: the row says `sentence` through `throttle`, stays live, and its
  next wake fires afresh."
  [eng id sentence]
  (when-some [row (raw-row eng :schedule id)]
    (try-act! eng row :throttle
              {:note (clip sentence)
               :retry_after (str (or (instant-of (now eng)) (Instant/now)))})))

;; ── the runner pool (waymark ticket d16b71bf) ──────────────────────

(defn- every-link [eng]
  (when (contains? (inv/resources eng) :runner_link)
    (rows-where eng :runner_link {} 100000)))

(defn links-pooled-by
  "The runner_link rows whose `pools` names this schedule or model, by
  that entry's rank (S1b of docs/spec-seat.md §22). A retired link is
  dropped; a broken one is kept, so a pool whose every link broke is
  held, not empty."
  [eng kind id]
  (let [links (every-link eng)
        by-id (into {} (map (juxt (comp str :id) identity)) links)]
    (mapv by-id (seats/pooled-ids links kind id))))

(defn pool-of
  "The runner link ids ONE fire of this schedule goes out through, in
  order, or nil when it has none. The schedule's own pool wins; else
  the chair's. A pool is the links whose `pools` name the row
  (`seats/pool-from`, which the doors read too)."
  [eng schedule-row seat-row]
  (seats/pool-from #(every-link eng)
                   schedule-row
                   (some->> (seats/chair-of seat-row) (raw-row eng :model))))

(defn pool-order-of
  "The order ONE fire of this schedule picks its pool's links in
  (waymark ticket 529deb73): the `runner_order` of the row whose pool
  `pool-of` took, \"least_used\" when it names none."
  [eng schedule-row seat-row]
  (let [own (seats/pooled-ids (every-link eng) :schedule (:id schedule-row))
        source (if own
                 schedule-row
                 (some->> (seats/chair-of seat-row) (raw-row eng :model)))]
    (or (some-> (get-in source [:data :runner_order]) str not-empty)
        "least_used")))

(defn fire-through-pool!
  "Fire through the pool `ids` and land the answer on the schedule. The
  run that started stamps `fired` with `last_runner`; a pool whose
  every link is waiting keeps the wake pending until the earliest of
  them is free (48dc648c's rule, across the pool); a pool a transient
  refusal left unstarted keeps the wake pending and tries again in a
  minute, as `land-answer!` does; a pool a link answered 400 lands
  that 400 as `land-answer!` does (ticket 225be373); a pool with no
  link that can fire says so.
  `provider-of` answers a link row's Provider.

  runner-links requires this namespace, so its `fire-pool!` is
  resolved at the call."
  [eng provider-of schedule-row text at ids & [order owner]]
  (let [fire-pool! (requiring-resolve 'waymark10.server.runner-links/fire-pool!)
        {:keys [runner answer retry-at transient paused skipped]}
        (fire-pool! eng provider-of ids text order
                    {:kind "schedule" :id (str (:id schedule-row))}
                    owner)
        ;; a contributor's seat (b7572409) says which links it passed by
        why (when skipped (str " Skipped — " skipped "."))]
    (cond
      ;; a pull link already holds a fire for this seat nobody took
      ;; (ticket a25950b4): no second one went out, and the row says
      ;; which fire waits, one maintenance write
      (:waiting answer)
      (store/with-tx (:storage eng)
        (fn [tx]
          (when-some [row (store/load-row (:storage eng) tx :schedule
                                          (str (:id schedule-row))
                                          {:for-update true})]
            (store/maintain! :stamp (:storage eng) tx :schedule (str (:id schedule-row))
                                (assoc (:data row) :note
                                       (clip (str "A fire for this seat already waits on pull runner link "
                                                  runner " for a localfire to take it (fire "
                                                  (:queued answer) "); no second fire was queued.")))
                                (:next-flip-at row)))))

      runner
      (try-act! eng schedule-row :fired
                (cond-> {:last_fired_at (str (or (instant-of at) (now eng)))
                         :last_runner runner}
                  (:started answer)
                  (assoc :last_run_url (str (:started answer)))))

      ;; the Routine is paused at the provider (tickets f1918540,
      ;; 225be373): a live or pending row is held, a held or paused one
      ;; stands still, as a 400 on the one link lands
      paused
      (land-answer! eng schedule-row {:status 400 :transient paused} at)

      ;; a transient refusal: the row stays live and tries again
      transient
      (throttle! eng schedule-row nil nil transient)

      ;; a pool whose localfires are all away waits for one (LP2): the
      ;; note names the link and when it was last seen
      retry-at
      (let [away ((requiring-resolve 'waymark10.server.runner-links/pool-away)
                  eng ids at owner)]
        (try-act! eng schedule-row :throttle
                  {:note (clip (if away
                                 (str away "; the wake goes out once it does." why)
                                 (str "Every runner link in this pool is waiting; the wake goes out at "
                                      retry-at "." why)))
                   :retry_after (str retry-at)}))

      :else
      (note! eng schedule-row
             (clip (str (if owner
                          (str "No runner link of " owner "'s in this pool can fire.")
                          "No runner link in this pool can fire: each is broken, retired or missing.")
                        why))))
    nil))

(defn- fire-sitting-of!
  "The id of the sitting this fire opens before any session sits in it
  (`seats/open-fire-sitting!`, ticket 9100c7b9), or nil. A fire through
  a runner pool opens one too (ticket 9f108b88): when a pull link holds
  it, the key's queue and take lease the sitting again
  (`seats/queue-fire-key!`, `seats/take-fire-key!`), so a laptop asleep
  is not a missed run. A failure here costs the fire nothing: its sit
  opens the sitting as before."
  [eng _schedule-row seat-row at]
  (try (some-> (seats/open-fire-sitting! eng seat-row at) :id str)
       (catch Exception e
         (warn! "seat " (:id seat-row) " fire opened no sitting — "
                (ex-message e))
         nil)))

(defn- fire-by-pool-or-link!
  "One fire of this schedule, through its pool. The runner link's pool
  is the only fire credential (ticket 9fa42626), so a schedule with no
  pool fires nothing."
  [eng adapter schedule-row text at seat-row]
  (when-some [ids (pool-of eng schedule-row seat-row)]
    ;; claude_routine and localfire links speak one wire, so one
    ;; Provider fires both (529deb73)
    (fire-through-pool! eng (constantly (claude-routine adapter))
                        schedule-row text at ids
                        (pool-order-of eng schedule-row seat-row)
                        ((requiring-resolve 'waymark10.server.runner-links/pool-owner)
                         eng seat-row))))

(defn held?
  "Does this row hold its wakes rather than fire them (waymark ticket
  bb19404d)? A row the provider broke — a 401, 403 or 404 — fires
  nothing until a person links it again, so a known-bad token is not
  sent at every wake and cadence tick. A row that fires through a pool
  holds only when no runner in it is live: a broken runner is passed
  over and the next live one fires."
  ([eng schedule-row]
   (held? eng schedule-row
          (raw-row eng :seat (get-in schedule-row [:data :seat]))))
  ([eng schedule-row seat-row]
   (if-some [ids (pool-of eng schedule-row seat-row)]
     (not-any? #(= "live" (some-> (raw-row eng :runner_link %) :state name)) ids)
     (= "broken" (some-> (:state schedule-row) name)))))

;; ── the consumer ────────────────────────────────────────────────────

(def consumer-name
  "The durable cursor's name in waymark10_cursors (consumer:schedules)."
  :schedules)

(defn- seat-create-actions
  "The action names this engine logs a seat BIRTH under. `:create` is
  the default and the spec's word; a kind may rename its create door,
  and reading the rdef costs one map lookup where guessing costs a
  seat that never gets a schedule."
  [eng]
  (or (some-> (get (inv/resources eng) :seat) :create-action-names set)
      #{:create}))

(defn- lift-the-fired-line!
  "Lift the halt line this fire passed (R-3 of waymark-fp62.7.13).

  ONLY THE LINE THE DOOR JUDGED. The door lets a fire out past one
  halt and one only: a `budget_reached` line whose week has fuel in it
  again. A line of any other wall never reaches here, because the door
  refuses it; and a line written AFTER this fire — a sitter of the run
  this fire started, at a wall of its own — is a newer record than the
  fire and must stand, or a drain that lags behind the wire would put
  out an alert nobody read.

  Best effort, `try-act!`'s posture and its reason: a throw inside a
  consumer parks the drain for every other seat, and the fire is still
  the fire."
  [eng seat-row t]
  (let [halt (get-in seat-row [:data :halt])
        since (instant-of (:since halt))
        at (instant-of (:at t))]
    (when (and (= "budget_reached" (str (:reason halt)))
               since at
               (not (pos? (compare since at))))
      (try
        (seats/seat-clear-halt! eng (:id seat-row))
        (catch Exception e
          (warn! "seat " (:id seat-row)
                 " fired but its halt line stands — " (ex-message e)))))))

(defn- heal-schedule!
  "Mint the schedule of an active fired seat that has none (ticket
  8be05155): a seat born interactive got no row at its birth, and a
  restate to fired is not a birth. Only a seat with NO row in any state
  is minted for; one whose only row is ended (fired → interactive →
  fired) is said on *err* and left, because reviving an ended row is a
  lifecycle decision `one-schedule-per-seat` and `ended` do not make
  here. Best effort, `try-act!`'s posture: a throw parks the drain."
  [eng seat-row]
  (if-some [row (schedule-for-seat eng (:id seat-row))]
    (when (= :ended (:state row))
      (warn! "seat " (:id seat-row) " is fired but its only schedule "
             (:id row) " is ended — no row is minted, so it cannot fire"))
    (try
      (ensure-schedule! eng seat-row)
      (catch Exception e
        (warn! "seat " (:id seat-row) " has no schedule and none could "
               "be minted — " (ex-message e))))))

(defn- end-row!
  "End a schedule row whose seat retired, merged or went interactive.
  The row's own lifecycle only: no provider copy is touched (ticket
  274a4150 disconnected the mirror). A row already ended is left alone."
  [eng schedule-row]
  (when-not (= :ended (:state schedule-row))
    (try
      (act! eng (:id schedule-row) :end nil)
      (catch Exception e (break! eng schedule-row e)))))

;; ── the pools a runner link carries (S1 of docs/spec-seat.md §22) ───

(defn- pool-entry
  "One entry of a link's `pools`, spelled one way whether it was just
  worked out or read back off the row."
  [e]
  (cond-> {:kind (some-> (:kind e) name)
           :id (some-> (:id e) str)
           :rank (some-> (:rank e) long)}
    (some? (:by e)) (assoc :by (name (:by e)))))

(def ^:private mended-after
  "The bookkeeping doors that keep a schedule where it stands (ticket
  5e0d33bb). After each, the consumer sends a pending, broken or held
  row through `mend`, whose guard asks whether it now fires."
  #{:fired :throttle})

(defn- mend-named!
  "A link made, restated, restored or given its pools (R-22.4): each
  broken schedule whose pool (`pool-of`) or whose chair's pool names it
  goes back to live through `mend` — when a live
  link in its pool would fire it, so a mend never meets the guard that
  refuses it."
  [eng link-id]
  (doseq [row (rows-where eng :schedule {:state :broken} 1000)
          :let [seat-row (some->> (get-in row [:data :seat]) str not-empty
                                  (raw-row eng :seat))
                chair (some->> seat-row seats/chair-of (raw-row eng :model))]
          :when (and seat-row
                     (some #{link-id} (concat (pool-of eng row seat-row)
                                              (seats/pool-from #(every-link eng)
                                                               nil chair)))
                     (not (held? eng row seat-row)))]
    (try-act! eng row :mend nil)))

(defn- link-births
  "The runner link's create verbs."
  [eng]
  (or (some-> (get (inv/resources eng) :runner_link) :create-action-names set)
      #{:create}))

(defn- input-of [t k]
  (let [ins (:inputs t)]
    (or (get ins k) (get ins (name k)))))


(defn- rank-pools!
  "A POOL DOOR RANKS THE POOL (ticket dd2a2916): after a person's
  `join_pool`, or a create that named pools, restate, as the engine, the
  ranks of the entries in each pool `moves` names ({:kind :id :rank} …)
  so the pool reads in join order with no `runners` list behind it. The
  link's entry at `runner-links/at-the-end` goes one past the last real
  rank; one past every place is drawn back to the end; and a named rank
  another link holds pushes that entry and each later one down one.
  Leaving closes no gap: order is read by rank. Each entry keeps who put
  it there. Best effort, as `mend-named!` is."
  [eng link-id moves]
  (when (contains? (inv/resources eng) :runner_link)
    (let [cap @(requiring-resolve 'waymark10.server.runner-links/at-the-end)
          pools-of #(mapv pool-entry (get-in % [:data :pools]))
          self? #(= link-id (str (:id %)))]
      (doseq [{:keys [kind id rank]} moves
              :when (and kind id)
              :let [in? #(and (= (name kind) (:kind %)) (= (str id) (:id %)))]]
        (try
          (let [links (rows-where eng :runner_link {} 100000)
                ranks (fn [link] (keep #(when (in? %) (:rank %)) (pools-of link)))
                mine (some->> links (filter self?) first ranks first)
                taken (mapcat ranks (remove self? links))
                last' (fn [rs] (if (seq rs) (inc (reduce max rs)) 0))
                restate! (fn [link f]
                           (let [pools (pools-of link)
                                 moved (mapv #(cond-> %
                                                (and (in? %) (some? (:rank %)))
                                                (update :rank f))
                                             pools)]
                             (when (not= pools moved)
                               (try
                                 (inv/invoke! eng :runner_link (str (:id link)) :set_pools
                                              {:pools moved} {:principal system-actor})
                                 (catch Exception e
                                   (warn! "runner link " (:id link) " keeps a stale rank in "
                                          (name kind) " " id " — " (ex-message e)))))))
                self (first (filter self? links))]
            (cond
              (nil? mine) nil

              (>= mine cap)
              (restate! self (constantly (last' (filter #(< % cap) taken))))

              (> mine (last' taken))
              (restate! self (constantly (last' taken)))

              (and (some? rank) (some #{mine} taken))
              (doseq [link (remove self? links)]
                (restate! link #(if (>= % mine) (inc %) %)))))
          (catch Exception e
            (warn! (name kind) " " id " could not rank runner link "
                   link-id "'s pool — " (ex-message e))))))))

(defn handle-transition!
  "One transition → what it implies for the schedule row, or nothing.

  THE PROVIDER MIRROR IS GONE (ticket 274a4150): no door here writes
  a provider's copy, and the middle argument is not read. The Routines
  API is fire-only, and the fields the mirror wrote are dropped
  (ticket 28814005).

  The doors it listens for are the seat's own, by action name
  (spec-seat R-4.4), plus the schedule's links:

      seat create           mint the row, stamp the seat
      seat merge, retire    end the row
      seat fire             start the linked Routine's run (R-12.19)
      schedule fired,       a pending or broken row that now fires goes
        throttle            live through `mend`, whose own transition
                            releases its wake; a paused row is left
                            where it stands (ticket 5e0d33bb)
      runner_link create,   each broken schedule whose pool names it,
        restate, restore,   mended once a link in that pool is live
        set_pools,
        join_pool,
        leave_pool

  Everything else — including every transition this namespace itself
  writes — is ignored, which is what keeps the consumer from feeding
  itself."
  [eng _adapters t]
  (let [kind (:kind t)
        action (:action t)]
    (cond
      (= :seat kind)
      (let [births (seat-create-actions eng)
            seat-row (raw-row eng :seat (:resource-id t))]
        ;; A FIRED SEAT WITH NO ROW GETS ONE on any transition but its
        ;; birth, merge or retire (ticket 8be05155) — so a seat restated
        ;; out of the interactive mode can fire, and a seat already
        ;; stranded heals on its next move. Before the `cond`, so a
        ;; `fire` below finds the row.
        (when (and seat-row
                   (= :active (:state seat-row))
                   (not (seats/interactive-seat? seat-row))
                   (not (contains? births action))
                   (not (contains? #{:merge :retire} action)))
          (heal-schedule! eng seat-row))
        (cond
          ;; AN INTERACTIVE SEAT HAS NO SCHEDULE (R-10.8). Nothing
          ;; fires it, so no row is minted — and a seat RESTATED into
          ;; the mode ends the row it already had. A row already
          ;; ended is left alone.
          (seats/interactive-seat? seat-row)
          (when-some [row (schedule-for-seat eng (:resource-id t))]
            (end-row! eng row))

          (contains? births action)
          (when seat-row
            (ensure-schedule! eng seat-row))

          (contains? #{:merge :retire} action)
          (when-some [row (schedule-for-seat eng (:resource-id t))]
            (end-row! eng row))

          ;; THE FIRE GOES OUT AFTER THE COMMIT (R-12.19). The door
          ;; already refused what a door can refuse, so an unlinked row
          ;; — or no row at all — is silence here and not a second
          ;; refusal; and a replayed transition whose fire already went
          ;; out is skipped rather than fired twice.
          (= :fire action)
          (do
            ;; AND THE LINE THE FIRE LIFTED (R-3 of
            ;; waymark-fp62.7.13). A fire that passed `not-halted`
            ;; passed a wall that no longer holds: the door summed the
            ;; week's fuel again and let this fire out. The lift goes
            ;; through the same `clear_halt` the router's own pass
            ;; uses — system actor, logged, idempotent — so the audit
            ;; reads the same whether a sitter's request or a fire
            ;; lifted the line. Post-commit, because the seat's row is
            ;; not this transaction's to move; and here rather than at
            ;; the door, so a person's fire and a wake's fire are one
            ;; rule (R-5): both write this one transition.
            (lift-the-fired-line! eng seat-row t)
            ;; THE TEXT IS COMPOSED HERE (R-3 of waymark-fp62.7.23),
            ;; from the seat row the door just moved. The log keeps
            ;; the prose a person wrote; what goes out carries the
            ;; seat's instructions around it, so a firing cannot run
            ;; on instructions nobody in this house can read. And the
            ;; link is this row's, or else the chair's (R-5).
            ;; A FIRE NAMING A ROW STILL IN ITS GRACE WAITS (ticket
            ;; afb445d4): a run sent now would be told the row is held
            ;; and stop, and the fire's text would be spent. The row
            ;; keeps the fire as a deferred wake, text and all, and the
            ;; tick's release sends it when the grace lifts.
            (when-some [row (schedule-for-seat eng (:resource-id t))]
              (when-not (already-fired? row (:at t))
               (if-some [lift (when-some [text (some-> (get-in t [:inputs :text])
                                                       str not-empty)]
                                (seats/fire-deferred-until
                                 eng seat-row text ((:now-fn eng))))]
                (defer-fire! eng row lift (str (get-in t [:inputs :text])))
                ;; AND THE KEY OF THIS ONE FIRING (R-12.37), minted
                ;; here because here is where the text is composed. The
                ;; engine mints 128 bits, the seat row keeps the hash,
                ;; and the key itself is on the wire one time, in the
                ;; line under the seat's. The hash is written BEFORE
                ;; the POST goes out, so a session that sits inside the
                ;; same second finds its key already held. A seat with
                ;; no instructions mints nothing: its Routine holds a
                ;; standing key of its own, and the text is the
                ;; person's prose alone.
                ;; The key also keeps the walk row a wake's text names,
                ;; so the sit hands the run that row (`seats/fire-key-row`).
                (fire-by-pool-or-link! eng (fire-adapter-of eng) row
                       (fire-text seat-row
                                  (some-> (get-in t [:inputs :text])
                                          str not-empty)
                                  (seats/hold-fire-key!
                                   eng seat-row (:at t)
                                   (some-> (get-in t [:inputs :text])
                                           str not-empty)
                                   ;; … and the sitting the fire opened
                                   ;; (ticket 9100c7b9)
                                   (fire-sitting-of! eng row seat-row (:at t))))
                       (:at t)
                       seat-row)))))))

      ;; A LINK RELEASES THE WAKE A BROKEN ROW HELD (waymark ticket
      ;; bb19404d), once: the release is keyed by this transition. A
      ;; restated pool is a link too (6e407ca7): `release!` still asks
      ;; `held?`, so a list with no live runner releases nothing.
      ;; A NEW LINK FIRES ON THE NEXT FIRE (R-22.4): a link made,
      ;; restated, restored or given its pools mends each broken row
      ;; whose pool names it, and `mend` releases the wake below.
      (and (= :runner_link kind)
           (contains? (into #{:restate :restore :set_pools :join_pool :leave_pool}
                            (link-births eng))
                      action))
      (let [link-id (str (:resource-id t))]
        (cond
          (= :join_pool action)
          (rank-pools! eng link-id [{:kind (input-of t :kind)
                                     :id (input-of t :id)
                                     :rank (input-of t :rank)
                                     :in? true}])

          ;; a create does not record (its input holds the token), so
          ;; its pools are read off the link as it stands
          (contains? (link-births eng) action)
          (rank-pools! eng link-id
                       (for [e (get-in (raw-row eng :runner_link link-id)
                                       [:data :pools])]
                         {:kind (:kind e) :id (:id e) :rank (:rank e) :in? true})))
        (mend-named! eng link-id))

      ;; ONLY `mend` MOVES A SCHEDULE INTO LIVE (ticket 5e0d33bb): a
      ;; bookkeeping door keeps the row where it stands, and a pending
      ;; or broken row it leaves firing is mended here. The mend is a
      ;; transition of its own, and this branch hears it and releases.
      (and (= :schedule kind)
           (contains? (conj mended-after :mend) action))
      (when-some [row (raw-row eng :schedule (:resource-id t))]
        (cond
          (and (contains? mended-after action)
               (contains? #{"pending" "broken" "held"} (some-> (:state row) name)))
          (try-act! eng row :mend nil)

          (and (= :mend action)
               (get-in row [:data :wake_pending]))
          ((requiring-resolve 'waymark10.server.wakes/release-linked!) eng row t))))))

(defn consumer-fn
  "The consumer's function of one transition. Public because a test
  drains it directly (`consumers/drain-consumer!`), which is how the
  framework's own consumer suite stays deterministic. No adapter is
  read: the provider mirror is disconnected (ticket 274a4150)."
  [eng]
  (fn [t] (handle-transition! eng nil t)))

(defn serving?
  "Does this engine serve the schedule kind? The module's `:when`, on
  the mirror module's precedent: a house that did not opt in starts no
  consumer and pays nothing for the module."
  [eng]
  (contains? (inv/resources eng) :schedule))

(def mirror-fields
  "What the provider mirror wrote on a schedule before ticket 274a4150
  disconnected it. Ticket 28814005 dropped them from the schema, and
  `drop-mirror-fields!` takes them off a row that still holds them."
  [:external_id :pushed_at :seen_at :drift])

(defn drop-mirror-fields!
  "Take `mirror-fields` off every schedule row that still holds one: a
  migration beside its kind, `seats/backfill-domain-ids!`'s spelling.
  The state, the version, the clock and every other field (the runner
  links among them) stay as they were, and a second pass writes
  nothing. → the number of rows written."
  [eng]
  (if-not (serving? eng)
    0
    (let [st (:storage eng)
          holds? (fn [row] (some #(contains? (:data row) %) mirror-fields))
          due (store/with-tx st
                (fn [tx]
                  (->> (store/query-rows st tx :schedule {} {:all true})
                       (filter holds?)
                       (mapv (comp str :id)))))]
      (count
       (filterv
        (fn [id]
          (store/with-tx st
            (fn [tx]
              (when-some [row (store/load-row st tx :schedule id
                                              {:for-update true})]
                (when (holds? row)
                  (store/update-data! st tx :schedule id
                                      (apply dissoc (:data row) mirror-fields)
                                      (:next-flip-at row))
                  true)))))
        due)))))

(defn start-mirror!
  "Register the durable log consumer that mirrors seats out. Returns
  the running consumer; `stop-mirror!` ends it. The elected start is
  where `drop-mirror-fields!` runs, once, before the consumer reads."
  ([eng] (start-mirror! eng {}))
  ([eng opts]
   (drop-mirror-fields! eng)
   (consumers/register-consumer! eng consumer-name (consumer-fn eng) opts)))

(defn stop-mirror! [running]
  (consumers/stop-consumer! running))

;; ── the fire's scriptable twin ──────────────────────────────────────
;;
;; The fake is part of the seam's definition, so it lives here and not
;; in the test tree. It records every fire in order, so a test
;; can say what the provider was asked to do and what it was told, and
;; it answers whatever status the test scripts — the four the spec
;; names, and any other.

(def ^:private fresh-fire
  {:fires [] :answer nil :seq 0})

(defrecord FakeFire [state]
  FireAdapter
  (fire-routine [_ fire-url token text]
    (let [s (swap! state
                   (fn [s]
                     (-> s
                         (update :seq inc)
                         (update :fires conj {:fire-url (str fire-url)
                                              :token (str token)
                                              :text (some-> text str not-empty)}))))
          answer (:answer s)]
      (if answer
        (throw (ex-info (str "the routines api answered " (:status answer)
                             " for the fire")
                        answer))
        {:session-id (str "session_01fake" (:seq s))
         :session-url (str "https://claude.ai/code/session_01fake" (:seq s))}))))

(defn fake-fire
  "A scriptable fire endpoint that has answered nobody yet."
  []
  (->FakeFire (atom fresh-fire)))

(defn fires
  "Every fire the adapter was asked for, in order —
  [{:fire-url :token :text} …]. The token is here because a test must
  prove the engine sent the one the row holds; nothing else ever reads
  it back."
  [fake]
  (:fires @(:state fake)))

(defn answer!
  "Script the provider's next answer, and every one after it: nil is a
  2xx carrying a session id and a run URL, a status is that status.
  `data` rides the thrown ex-data, which is where `:retry-after`
  lives — (answer! fake 429 {:retry-after \"30\"})."
  ([fake status] (answer! fake status nil))
  ([fake status data]
   (swap! (:state fake) assoc :answer
          (when status (merge {:status status} data)))
   nil))

(defn reset-fire! [fake]
  (reset! (:state fake) fresh-fire))
