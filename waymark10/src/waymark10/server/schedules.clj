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
     `live`, `paused`, `broken`.
  3. THE OPERATIONS ARE NOT push/pull. `MirrorAdapter` writes
     documents; a scheduler is paused, resumed and deleted, and
     `calendar10/source` had to hang `delete-event!` off the side of
     the protocol to say so in its own docstring. Six operations,
     named, are honest where four plus an escape hatch are not.

  So the seam here is `ScheduleAdapter`, provider-keyed, and the
  bookkeeping (`external_id`, `pushed_at`, `seen_at`, `drift`) is
  ordinary data on an ordinary kind with hidden system doors — the
  mirror's own posture for its sync writes, borrowed without the
  machine.

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
  (`schedule.write`), on `feed.preview_as`'s pattern, and a
  deployment's boot seed ensures it the way workqueue10's
  `ensure-capabilities!` ensures the Gate tokens. Nothing grants it —
  naming it is what makes the power auditable.

  A deployment with no token configured for a provider does not fail
  to boot. `from-env` answers a `NoCredential` adapter for that
  provider, whose every operation throws the sentence saying so, and
  the first push lands the schedule in `broken` with that sentence as
  its note. That is a boot that says so.

  ── what is not built here ─────────────────────────────────────────

  - `jules` and `cron` are in the enum and have no adapter; a
    deployment gets `NoCredential` for both, which reads exactly as
    R-12.11 says it should.
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
  token onto this row through `link`; the engine fires it from then
  on and never writes it.

  A LINKED row (`fire_url` present) is a row a person manages, so the
  adapter leaves it alone: `push!`, `pause!` and `resume!` call no
  operation and move no field, and `delete!` still ends the row. The
  `link` is the whole of the engine's knowledge of that Routine.

  The fire itself is a second seam, `FireAdapter`, with one
  operation. It runs AFTER the commit, in this same consumer, when
  the seat's own `fire` door is heard: the door refuses what a door
  can refuse (parked, halted, unlinked, a bare agent) and the
  provider's own answer lands on this row as a state and a note —
  `fired` on a 2xx, `paused` on a 400, `broken` with the sentence on
  a 429, a 401 or a 404. A replay is deduped by `last_fired_at`, and
  nothing here ever re-throws: a throwing consumer parks the drain.

  ── the engine opt a deployment owes ───────────────────────────────

  `adapters-of` reads `(:schedule-adapters eng)` first and falls back
  to the environment. `server/engine.clj` whitelists both
  `:schedule-adapters` and `:schedule-drift-ms`; a deployment may pass
  adapters by value, and the env is R-12.11's path when it does not.
  `fire-adapter-of` reads `(:fire-adapter eng)` the same way, and the
  real one needs no environment at all: the URL and the token are
  fields of the row it is firing."
  (:require [clojure.string :as str]
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
           (java.time Duration Instant)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

;; ── the adapter seam ────────────────────────────────────────────────

(defprotocol ScheduleAdapter
  "One provider's scheduler, as six operations. A copy is addressed by
  the provider's own id; the spec handed to create/update is the
  canonical shape every adapter translates:

      {:name         \"inbox-clerk\"        ; the seat's name
       :cron         \"0 * * * *\"          ; cadence-seconds, spelled
       :model        \"claude-sonnet-5\"    ; the model row's name, or nil
       :prompt       \"You sit in the seat …\"   ; create only, frozen
       :client-token \"<schedule row id>\"}      ; create only, the dedupe

  Every operation throws on unreachable, refused, or missing
  credential; the caller lands the throw as `broken` with the
  exception's message as the row's note."
  (create-copy [a spec]
    "Create the provider's copy → {:external-id id}. `:client-token`
    is the schedule row's id: a provider that honours it answers the
    SAME copy on a replay, which is what makes the at-least-once
    consumer safe to re-run.")
  (update-copy [a external-id spec]
    "Write name, cron and model onto the existing copy. The PROMPT is
    deliberately not sent: R-12.3 freezes it at creation, and the way
    to keep a value fixed is to stop writing it.")
  (pause-copy [a external-id]
    "The copy stops firing and keeps its settings (the seat parked).")
  (resume-copy [a external-id]
    "The copy fires again (the seat unparked).")
  (delete-copy [a external-id]
    "The copy is removed (the seat retired or merged). Deleting a copy
    that is already gone SUCCEEDS — the consumer replays.")
  (read-copy [a external-id]
    "The copy as the provider holds it →
    {:external-id :name :cron :model :prompt :enabled} — the
    read-back the drift sweep compares against the row."))

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
  made, which is why `update-copy` does not send it."
  [seat-name]
  (str "You sit in the seat `" seat-name "`. Read the seat row with "
       "`waymark_get` and do what its charter says. Take only the doors "
       "the envelope offers. When the seat says halted or parked, say "
       "why and stop."))

;; ── the capability (R-12.11) ────────────────────────────────────────

(def write-capability-token
  "The dotted token the registry names for a schedule adapter's
  credential. Spelled here rather than at three literals, on
  `capabilities/feed-preview-as-token`'s precedent."
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
  when it does."
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

(def no-link-note
  "The note an unlinked row carries (R-12.18), spelled once so the
  door and the test read the same words."
  "No link: the Routine's fire URL and token are not on this schedule.")

(def ^:private engine-writes
  "The safety sentence every engine-written door here wears. The
  mirror's `sync-safety` is the precedent, and the reason is the
  same: these doors are bookkeeping, the row comes back by another
  door, and a `:reversible true` would promise a reverse a person
  could actually take — which is exactly what a hidden door is not."
  {:idempotent true :reversible false :confirm false
   :one-way "Bookkeeping the engine writes as it mirrors the seat out; the next push or read-back moves the row again."})

(defhandler claim-copy
  [row inp _ctx]
  (-> row
      (assoc-in [:data :external_id] (:external_id inp))
      (assoc-in [:data :pushed_at] (:pushed_at inp))
      (update :data dissoc :note)))

(defhandler record-read-back
  [row inp _ctx]
  (-> row
      (assoc-in [:data :seen_at] (:seen_at inp))
      (assoc-in [:data :drift] (:drift inp))))

(defhandler record-break
  [row inp _ctx]
  (assoc-in row [:data :note] (:note inp)))

(defhandler restate-model
  [row inp _ctx]
  (assoc-in row [:data :model] (:model inp)))

(defhandler forget-copy
  [row _inp _ctx]
  (update row :data dissoc :external_id))

(defhandler write-link
  [row inp _ctx]
  (-> row
      (assoc-in [:data :fire_url] (:fire_url inp))
      (assoc-in [:data :fire_token] (:token inp))
      (update :data dissoc :note)))

(defhandler clear-link
  [row _inp _ctx]
  (-> row
      (update :data dissoc :fire_url :fire_token)
      (assoc-in [:data :note] no-link-note)))

(defhandler stamp-fire
  [row inp _ctx]
  (-> row
      (assoc-in [:data :last_fired_at] (:last_fired_at inp))
      (cond-> (:last_run_url inp)
        (assoc-in [:data :last_run_url] (:last_run_url inp)))
      (update :data dissoc :note)))

(defresource schedule
  {:kind :schedule
   :plural "schedules"
   :states [:pending :live :paused :broken :ended]
   :initial :pending
   :terminal #{:ended}
   :nav :system
   :summary "{data.provider} · {state}"
   :label-template "{data.provider} schedule"
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
                 :help "The harness's own scheduler — the one that actually wakes the model on the cadence. The engine mirrors this row out to it and reads it back."
                 :choices {"claude_routine" "A Claude Routine — the built adapter."
                           "jules" "A scheduled Jules session — named, not yet built."
                           "cron" "An ordinary cron entry — named, not yet built."}}}
     (into [:enum] providers)]
    [:model {:optional true :kind :model
             :x-display
             {:label "The model the firing starts"
              :help "Defaults to the first of the seat's held-for list when the schedule is opened. Restate it to send a cheaper model for a day, or a substitute's."}}
     [:maybe :waymark/ref]]
    ;; the four engine-written facts. They RENDER — a person reading a
    ;; schedule wants to know when it was last written and last seen —
    ;; and each says out loud that no form is asking (waymark-0ee).
    [:external_id {:optional true
                   :x-display
                   {:label "The provider's id for the copy"
                    :help "Written by the adapter when it makes the copy; blank until then, and blank again after the copy is deleted."}}
     [:maybe [:string {:min 1 :max 200}]]]
    [:pushed_at {:optional true
                 :x-display
                 {:label "Last pushed"
                  :help "When the adapter last wrote the copy. Written by the engine, never by hand."}}
     [:maybe :waymark/instant]]
    [:seen_at {:optional true
               :x-display
               {:label "Last read back"
                :help "When the adapter last read the copy back to check it. Written by the engine, never by hand."}}
     [:maybe :waymark/instant]]
    [:drift {:optional true
             :x-display
             {:widget "prose"
              :label "What the read-back found"
              :help "What the provider's copy says that this row does not. Reported, never repaired: a difference here is a question for a person, and the row is the truth."}}
     [:maybe [:string {:max 280}]]]
    [:note {:optional true
            :x-display
            {:widget "prose"
             :label "Why this schedule is broken"
             :help "The adapter's own sentence about why it could not reach the provider — a missing credential reads exactly as one. Cleared by the next successful push."}}
     [:maybe [:string {:max 280}]]]
    ;; ── the fire link (R-12.18) ─────────────────────────────────────
    ;; The URL is shown; the token never is. A linked row is a row a
    ;; person manages: the adapter above leaves it alone, and the fire
    ;; below is the only thing the engine does with it.
    [:fire_url {:optional true
                :x-display
                {:raw true
                 :label "The Routine's fire URL"
                 :help "The one endpoint that starts a run of the Routine you made by hand. Paste it here with the token, through Link. The engine never makes, changes or reads a Routine."}}
     [:maybe [:string {:min 1 :max 400}]]]
    ;; THE TOKEN IS HELD AS THE SEAT HOLDS sitter_key (R-12.11): one
    ;; writing door, never rendered, never filterable, and never in a
    ;; transition's recorded inputs — which is why `link` does not
    ;; record (see the deviations).
    [:fire_token {:optional true :secret true
                  :x-display
                  {:hidden true
                   :label "The Routine's token"
                   :spelled-by-hand "Written by Link and cleared by Unlink; never shown again, and never asked for by a form that already holds it."}}
     [:maybe [:string {:min 16 :max 400}]]]
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
    ;; write and no transition (the `stamp-seen!` pattern); it is
    ;; declared here so the field exists the day the wake consumer
    ;; lands, and so a reader of this row can see why a match did not
    ;; fire.
    [:wake_pending {:optional true
                    :x-display
                    {:label "A wake is waiting"
                     :help "Set by the engine when a transition matched this seat's wake_on inside the damper. The next fire after the damper lifts clears it."}}
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
     [:maybe :waymark/instant]]]
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
                :provider #{:eq :in}
                :external_id #{:eq}}
   :sortable {:fields [:created_at :updated_at] :default "-created_at"}
   :create-guards [engine-writes-schedules one-schedule-per-seat]
   :actions
   {:claim
    {:from #{:pending :live :paused :broken} :to :live
     :input [:map
             [:external_id {:x-display {:hidden true}}
              [:string {:min 1 :max 200}]]
             [:pushed_at {:x-display {:hidden true}} :waymark/instant]]
     :record true
     :guards [engine-writes-schedules]
     :edit {:prefill [:external_id] :fence false
            :unfenced-reason
            "Written by the push pass inside the log consumer; no read preceded it to fence against."}
     :safety engine-writes
     :handler claim-copy
     :display {:label "Copy written"}}

    :observe
    {:from #{:live} :to :live
     :input [:map
             [:seen_at {:x-display {:hidden true}} :waymark/instant]
             [:drift {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 280}]]]]
     :guards [engine-writes-schedules]
     :edit {:prefill [:seen_at :drift] :fence false
            :unfenced-reason
            "A system-only read-back inside the drift sweep; no human read preceded it to fence against."}
     :safety {:idempotent true :reversible false :confirm false}
     :handler record-read-back
     :display {:label "Copy read back"}}

    :pause
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

    :fail
    {:from #{:pending :live :paused :broken} :to :broken
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
    {:from #{:pending :live :paused :broken} :to :ended
     :guards [engine-writes-schedules]
     :safety {:idempotent true :reversible false :confirm false
              :final "The seat closed and its copy is deleted at the provider; opening a seat again opens a new schedule."}
     :handler forget-copy
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
     :edit {:prefill [:model] :fence true}
     :safety {:idempotent true :reversible false :confirm false}
     :handler restate-model
     :display {:label "Restate the model" :order 1}}

    ;; ── the fire link (R-12.18) ─────────────────────────────────────
    ;; Two more human doors, and they are the only place the token is
    ;; ever spelled. A second link REPLACES the first: a person who
    ;; rotates the Routine's token pastes the new one and nothing else
    ;; changes.
    :link
    {:from #{:pending :live :paused :broken} :to :live
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
     ;; NOT :record, and the seat's `offer_key` reason verbatim: a
     ;; recorded action persists its RAW inputs into the transition
     ;; log, and this input IS the credential (R-12.11: "never in a
     ;; transition's recorded inputs"). The transition row — actor,
     ;; input digest, summary — is still the audit that a link was
     ;; made, by whom, when.
     :guards [a-person-or-a-delegate]
     :edit {:prefill [:fire_url] :fence false
            :unfenced-reason
            "The token comes from the Routine's own page, not from this row; a link replaces what stands rather than editing it."}
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The link replaces whatever this row held; Unlink takes it off again."}
     :handler write-link
     :display {:label "Link the Routine" :style :primary :order 2
               :description "Paste the fire URL and the token of the Routine you made by hand — the engine fires it from then on"}}

    :unlink
    {:from #{:live :paused :broken} :to :broken
     :guards [a-person-or-a-delegate]
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The fire URL and the token leave this row; linking again means pasting both once more."}
     :handler clear-link
     :display {:label "Unlink the Routine" :style :danger :order 3
               :description "The engine forgets the fire URL and the token; nothing wakes this seat until it is linked again"}}

    ;; the fire's landing (R-12.19). Hidden, engine-written, and the
    ;; one door that clears a 429's note: the next fire that goes out
    ;; says the Routine has a free run again.
    :fired
    {:from #{:pending :live :paused :broken} :to :live
     :input [:map
             [:last_fired_at {:x-display {:hidden true}} :waymark/instant]
             [:last_run_url {:optional true :x-display {:hidden true}}
              [:maybe [:string {:max 400}]]]]
     :record true
     :guards [engine-writes-schedules]
     :edit {:prefill [:last_fired_at] :fence false
            :unfenced-reason
            "Stamped by the fire consumer the moment the provider answered; no read preceded it to fence against."}
     :safety engine-writes
     :handler stamp-fire
     :display {:label "Routine fired"}}}
   :deviations
   ["The schedule is NOT declared through server/mirror, though R-12.0 names the calendar as the precedent. Three reasons: mirror's authority points inward (a pull wins; R-12.3 wants a read-back that reports and never repairs), mirror refuses a kind that declares its own :states (R-12.1 names four), and MirrorAdapter has no pause, resume or delete (calendar10 had to hang delete-event! off the side of the protocol). The seam is ScheduleAdapter instead, and the bookkeeping posture — hidden system doors over ordinary data fields — is borrowed from mirror whole."
    "R-12.1 lists four states; this kind has five. `ended` is where a retired or merged seat's schedule lands once the copy is deleted. The alternative was returning the row to `pending`, which means \"no copy yet\" and invites the next push to make one."
    "R-12.1's field table does not list `note`, but its sentence for the `broken` state says \"the note says why\" and R-12.11 asks the kind to serve a broken provider \"with a note saying so\". So `note` is a field, engine-written, and the `fail` door records it in the log as well (the log's inputs column, R-4.7's spelling)."
    "R-12.2 calls the push a post-commit effect at the wire boundary. It is a durable log consumer here, which is what the brief asked to be built and the more honest of the two under a crash: the log is the record, and an effect that dies takes its push with it. Wave two may move it (waymark-442.14)."
    "R-12.6's ceiling is not pushed to the provider. The Routines API is not pinned in this repository, and R-12.6 names the fallback itself — the walk's `rows_per_firing` caps the firing. One field on the create payload when the real API is known."
    "R-12.18 asks `link` to record. It does NOT record here, and the seat's `offer_key` made the same trade for the same reason: a recorded action persists its raw inputs into the transition log, and this input is the token — which R-12.11 says is never in a transition's recorded inputs. The transition row still says a link was made, by whose hand and when."
    "R-12.20 writes the 429 as a refusal sentence. It lands here as a NOTE on a broken row instead. The fire goes out after the commit, so the provider's answer arrives when the door is already closed and there is nobody left to refuse; the row says what the provider said, and the next fire that goes out clears it."
    "`fired` accepts a `paused` and a `pending` row as well as `live` and `broken`, where R-12.18 names two states. A linked row is left alone by push, pause and resume, so a row a 400 moved to `paused` has no other way back to `live`; a fire that the provider answers is the evidence that heals it."]})

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

(defn- model-name
  "A model ref → the API identifier the provider wants. A house with no
  `model` kind registered, or a ref to a row that is gone, answers nil
  — the provider then uses its own default, which is the honest thing
  to do about a name we cannot supply."
  [eng model-id]
  (some-> (raw-row eng :model model-id) :data :name str))

(defn copy-spec
  "The copy this seat and this schedule together ask for: the three
  mirrored facts, and nothing else. The prompt is not here because it
  is frozen (see `pointer-prompt`); `create-spec` adds it once."
  [eng seat-row schedule-row]
  {:name (str (get-in seat-row [:data :name]))
   :cron (cron-of (get-in seat-row [:data :cadence_seconds]))
   :model (model-name eng (get-in schedule-row [:data :model]))})

(defn- create-spec [eng seat-row schedule-row]
  (assoc (copy-spec eng seat-row schedule-row)
         :prompt (pointer-prompt (get-in seat-row [:data :name]))
         :client-token (str (:id schedule-row))))

;; ── drift ───────────────────────────────────────────────────────────

(defn- clip [s]
  (let [s (str s)]
    (if (<= (count s) 280) s (str (subs s 0 277) "..."))))

(defn drift-of
  "What the provider's copy says that the row does not — one sentence,
  or nil when the two agree.

  The prompt is deliberately NOT compared: R-12.3 freezes it at
  creation, so a copy whose prompt no longer matches the prompt this
  seat's CURRENT name would generate is the design working, not
  drift. What is compared is the three mirrored facts and whether the
  copy is firing at all — a Routine somebody paused by hand is the
  drift most worth catching, because the seat looks live and nothing
  wakes."
  [spec state copy]
  (let [want-enabled (= :live state)
        diff (fn [label want got]
               (when (not= (some-> want str not-empty)
                           (some-> got str not-empty))
                 (str label " is " (pr-str got) " at the provider, "
                      (pr-str want) " here")))
        sentences (cond-> (into []
                                (keep identity)
                                [(diff "name" (:name spec) (:name copy))
                                 (diff "cron" (:cron spec) (:cron copy))
                                 (diff "model" (:model spec) (:model copy))])
                    (not= want-enabled (boolean (:enabled copy)))
                    (conj (str "the copy is "
                               (if (:enabled copy) "firing" "paused")
                               " at the provider, and this row says "
                               (name state))))]
    (when (seq sentences)
      (clip (str/join "; " sentences)))))

;; ── the adapters ────────────────────────────────────────────────────

(defn credential-missing
  "The one exception a deployment with no token throws. It carries the
  sentence the row's note will hold, so the note and the log say the
  same words."
  [why]
  (ex-info why {:waymark10/schedule-credential-missing true}))

(defrecord NoCredential [why]
  ScheduleAdapter
  (create-copy [_ _] (throw (credential-missing why)))
  (update-copy [_ _ _] (throw (credential-missing why)))
  (pause-copy [_ _] (throw (credential-missing why)))
  (resume-copy [_ _] (throw (credential-missing why)))
  (delete-copy [_ _] (throw (credential-missing why)))
  (read-copy [_ _] (throw (credential-missing why))))

(defn no-credential
  "The adapter a provider gets when this deployment cannot reach it.
  Boot does not fail (R-12.11); the first push lands `broken` with
  `why` as the note."
  [why]
  (->NoCredential why))

;; ── the Claude Routine adapter (R-12.11) ────────────────────────────
;;
;; THE API IS NOT PINNED IN THIS REPOSITORY. Every request and
;; response shape below is an ASSUMPTION, written down in the
;; operation's own docstring so that correcting it is one function
;; each rather than an archaeology. The wire is java.net.http, the
;; repository's one HTTP client (server/gate_proxy.clj and
;; calendar10/source.clj both use it), and the whole surface is these
;; seven functions — six operations and the call that carries them.

(def routines-url-env "WAYMARK10_ROUTINES_URL")
(def routines-token-env "WAYMARK10_ROUTINES_TOKEN")

(defn- routines-call!
  "One call to the Routines API → the parsed body (nil for 204).

  Non-2xx throws ex-info carrying :status. The body is parsed only
  AFTER the status is judged, which is calendar10's recorded lesson
  (waymark-t6s): a feed behind a restarting proxy answers plain-text
  'Bad Gateway', and parsing first turns an honest 502 into a parse
  exception nobody's catch expects."
  [{:keys [^HttpClient client token-fn base]} method path body]
  (let [url (str base path)
        publisher (if body
                    (HttpRequest$BodyPublishers/ofString
                     (wire/write-json body) StandardCharsets/UTF_8)
                    (HttpRequest$BodyPublishers/noBody))
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 20))
                (.header "authorization" (str "Bearer " (token-fn)))
                (.header "content-type" "application/json")
                (.method ^String method publisher)
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)]
    (when (>= status 400)
      (throw (ex-info (str "the routines api answered " status " for "
                           method " " path)
                      {:status status :body (.body resp)})))
    (try
      (some-> (.body resp) not-empty wire/read-json)
      (catch Exception _
        (throw (ex-info (str "the routines api answered " status
                             " with a body that is not JSON")
                        {:status status :body (.body resp)}))))))

(defn- routine->copy
  "One routine, as the API is assumed to answer it, → the canonical
  read-back. `enabled` is assumed to be a boolean; a provider that
  spells the same fact as a status string (\"active\"/\"paused\")
  needs one line here and nothing else."
  [r]
  {:external-id (some-> (:id r) str)
   :name (some-> (:name r) str)
   :cron (some-> (:cron r) str)
   :model (some-> (:model r) str)
   :prompt (some-> (:prompt r) str)
   :enabled (boolean (:enabled r))})

(defrecord ClaudeRoutines [^HttpClient client token-fn base]
  ScheduleAdapter

  (create-copy [this spec]
    ;; ASSUMED: POST {base}/routines
    ;;   → {"name": …, "cron": …, "model": …, "prompt": …,
    ;;      "client_token": …}
    ;;   ← 201 {"id": "rt_…", "name": …, "cron": …, "model": …,
    ;;          "prompt": …, "enabled": true}
    ;; client_token is the schedule row's id and the create's dedupe:
    ;; a replay is assumed to answer the SAME routine rather than a
    ;; second one. A provider without it needs a read-by-name before
    ;; the create, here and nowhere else.
    (let [created (routines-call!
                   this "POST" "/routines"
                   (cond-> {:name (:name spec)
                            :cron (:cron spec)
                            :prompt (:prompt spec)
                            :client_token (:client-token spec)}
                     (:model spec) (assoc :model (:model spec))))]
      {:external-id (or (some-> (:id created) str not-empty)
                        (throw (ex-info "the routines api minted no id"
                                        {:body created})))}))

  (update-copy [this external-id spec]
    ;; ASSUMED: PATCH {base}/routines/{id}
    ;;   → {"name": …, "cron": …, "model": …}
    ;;   ← 200 {"id": …, …}
    ;; The prompt is not in the body, and that absence is the whole of
    ;; R-12.3's "it never changes after the copy is made".
    (routines-call! this "PATCH" (str "/routines/" external-id)
                    (cond-> {:name (:name spec) :cron (:cron spec)}
                      (:model spec) (assoc :model (:model spec))))
    nil)

  (pause-copy [this external-id]
    ;; ASSUMED: POST {base}/routines/{id}/pause ← 200 {"id": …,
    ;; "enabled": false}
    (routines-call! this "POST" (str "/routines/" external-id "/pause") nil)
    nil)

  (resume-copy [this external-id]
    ;; ASSUMED: POST {base}/routines/{id}/resume ← 200 {"id": …,
    ;; "enabled": true}
    (routines-call! this "POST" (str "/routines/" external-id "/resume") nil)
    nil)

  (delete-copy [this external-id]
    ;; ASSUMED: DELETE {base}/routines/{id} ← 204, and 404 for a
    ;; routine already gone — which SUCCEEDS here, because the
    ;; consumer replays and a delete that refuses a second time would
    ;; park the drain forever.
    (try
      (routines-call! this "DELETE" (str "/routines/" external-id) nil)
      (catch clojure.lang.ExceptionInfo e
        (when-not (= 404 (:status (ex-data e)))
          (throw e))))
    nil)

  (read-copy [this external-id]
    ;; ASSUMED: GET {base}/routines/{id}
    ;;   ← 200 {"id": …, "name": …, "cron": …, "model": …,
    ;;          "prompt": …, "enabled": true}
    (routine->copy (routines-call! this "GET"
                                   (str "/routines/" external-id) nil))))

(defn claude-routines
  "The real boundary over the harness's Routines scheduler. config:
  :token-fn (a zero-arg token source), :base (the API base)."
  [{:keys [token-fn base]}]
  (->ClaudeRoutines
   (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) (.build))
   token-fn
   (str base)))

(defn from-env
  "provider → adapter for this deployment, off the environment.

  A provider with no credential gets `no-credential` rather than
  nothing: R-12.11 asks for a boot that SAYS SO, and an absent key
  would leave the consumer with no adapter and no sentence. `jules`
  and `cron` are named in the enum and have no adapter yet, which
  reads through the same door."
  ([] (from-env #(System/getenv ^String %)))
  ([env]
   (let [url (some-> (env routines-url-env) str not-empty)
         token (some-> (env routines-token-env) str not-empty)]
     {:claude_routine
      (cond
        (nil? url)
        (no-credential
         (str "No " routines-url-env " is configured, so this engine does "
              "not know where the Routines scheduler answers; the seat's "
              "copy was never made."))

        (nil? token)
        (no-credential
         (str "No " routines-token-env " is configured, so this engine "
              "holds no credential for the Routines scheduler; the seat's "
              "copy was never made."))

        :else
        (claude-routines {:token-fn (constantly token) :base url}))

      :jules
      (no-credential
       (str "No adapter is built for the Jules scheduler yet, so this "
            "engine cannot make the seat's copy there."))

      :cron
      (no-credential
       (str "No adapter is built for cron yet, so this engine cannot make "
            "the seat's copy there."))})))

(defn adapters-of
  "The provider → adapter map this engine mirrors through. The engine
  opt wins where it is given (the tests' seam, and offline dev's), and
  the environment is the deployment path."
  [eng]
  (merge (from-env) (:schedule-adapters eng)))

;; ── the fire seam (R-12.18 to R-12.20) ──────────────────────────────
;;
;; ONE operation, because the API has one endpoint. Unlike the six
;; above, this one is PINNED: the URL, the three headers, the body and
;; the answer are the facts recorded on waymark-fp62.7.3, not
;; assumptions. The credential is not the engine's either — it is the
;; row's `fire_token`, which a person pasted through `link` — so the
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
            (store/update-data!
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
    (act! eng (:id row) action body)
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

(defn linked?
  "Does a person manage this row's Routine by hand (R-12.18)? A linked
  row carries a fire URL, and the adapter of R-12.2 leaves it alone."
  [schedule-row]
  (boolean (some-> (get-in schedule-row [:data :fire_url]) str not-empty)))

(defn- adapter-for [adapters row]
  (let [p (keyword (str (get-in row [:data :provider])))]
    (or (get adapters p)
        (no-credential
         (str "No adapter is configured for the provider "
              (pr-str (str (get-in row [:data :provider])))
              ", so this engine cannot make the seat's copy.")))))

(defn push!
  "Write the provider's copy from the seat and the schedule, and claim
  what the provider minted.

  No external id yet → a CREATE carrying the schedule row's id as the
  client token; an id already → an UPDATE, which sends name, cron and
  model and never the prompt. Either way the landing is `claim`,
  which stamps `pushed_at`, clears the note and moves the row to
  `live` — so a replay re-writes the same copy and re-stamps the same
  three facts, which is the whole of at-least-once here.

  A throw lands `broken` with the exception's sentence as the note,
  and RETURNS rather than re-throwing: a consumer that threw would
  park its cursor on a provider outage and stop hearing about every
  other seat.

  A LINKED row is skipped whole (R-12.18): its Routine was made by
  hand and this engine has no endpoint that could write it."
  [eng adapters schedule-row]
  (let [seat-id (get-in schedule-row [:data :seat])
        seat-row (raw-row eng :seat seat-id)]
    (cond
      (= :ended (:state schedule-row)) nil

      (linked? schedule-row) nil

      (nil? seat-row)
      (do (warn! "schedule " (:id schedule-row) " names seat " seat-id
                 ", which this engine cannot read — nothing pushed")
          nil)

      :else
      (let [adapter (adapter-for adapters schedule-row)
            xid (some-> (get-in schedule-row [:data :external_id]) str not-empty)]
        (try
          (let [xid (if xid
                      (do (update-copy adapter xid
                                       (copy-spec eng seat-row schedule-row))
                          xid)
                      (str (:external-id
                            (create-copy
                             adapter
                             (create-spec eng seat-row schedule-row)))))]
            ;; an input crosses the wire boundary: the invoke path
            ;; digests it canonically before the schema coerces it, so
            ;; the instant travels as its string (mirror.clj's own
            ;; spelling for synced_at)
            (act! eng (:id schedule-row) :claim
                  {:external_id xid :pushed_at (str (now eng))}))
          (catch Exception e
            (break! eng schedule-row e)))))))

(defn pause!
  "Park the seat: the copy stops firing and keeps its settings. A row
  that is already paused, ended or never pushed is left alone — the
  transition would 409 and park the drain. A LINKED row is left alone
  too: a person manages that Routine, and parking the seat is already
  the wall the fire door refuses at (R-12.18, R-12.20)."
  [eng adapters schedule-row]
  (when-some [xid (some-> (get-in schedule-row [:data :external_id]) str not-empty)]
    (when (and (= :live (:state schedule-row))
               (not (linked? schedule-row)))
      (try
        (pause-copy (adapter-for adapters schedule-row) xid)
        (act! eng (:id schedule-row) :pause nil)
        (catch Exception e (break! eng schedule-row e))))))

(defn resume!
  "Unpark the seat. A row that is not paused is left alone; a row that
  is BROKEN goes back through `push!`, because the copy may never have
  been made and resuming a copy that does not exist is not a repair.

  A LINKED row is left alone (R-12.18), the paused case included: the
  row's own state there is the provider's answer to a fire, not a
  park, and only a fire that goes out moves it."
  [eng adapters schedule-row]
  (when-not (linked? schedule-row)
    (case (:state schedule-row)
      :paused (if-some [xid (some-> (get-in schedule-row [:data :external_id])
                                    str not-empty)]
                (try
                  (resume-copy (adapter-for adapters schedule-row) xid)
                  (act! eng (:id schedule-row) :resume nil)
                  (catch Exception e (break! eng schedule-row e)))
                (push! eng adapters schedule-row))
      :broken (push! eng adapters schedule-row)
      nil)))

(defn delete!
  "Retire or merge the seat: the copy is removed and the row ends. The
  delete is idempotent at the adapter (a copy already gone succeeds),
  so a replay reaches `end` and stops there.

  A LINKED row ends too, and no adapter is called (R-12.18): the
  Routine a person made by hand stays where it is, and this row stops
  pointing at it."
  [eng adapters schedule-row]
  (when-not (= :ended (:state schedule-row))
    (let [xid (some-> (get-in schedule-row [:data :external_id]) str not-empty)]
      (try
        (when (and xid (not (linked? schedule-row)))
          (delete-copy (adapter-for adapters schedule-row) xid))
        (act! eng (:id schedule-row) :end nil)
        (catch Exception e (break! eng schedule-row e))))))

;; ── the fire (R-12.19, R-12.20) ─────────────────────────────────────

(defn- instant-of
  "An instant, however the row or the log spells it — a stored string
  or the log's own Instant. Unparsable is nil, which reads as \"no
  fire on record\" and fires."
  [v]
  (cond
    (instance? Instant v) v
    (some-> v str not-empty) (try (Instant/parse (str v))
                                  (catch Exception _ nil))
    :else nil))

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

(defn fire!
  "Start one run of this row's linked Routine, and land the provider's
  answer on the row.

  2xx stamps `last_fired_at` and `last_run_url` through `fired`, which
  also clears the note — so the first fire that goes out heals a row a
  429 broke. A 400 is the provider saying the Routine is paused: the
  row pauses where it can, and says the sentence where it cannot. A
  401, a 404 and anything else land as a note on a broken row.

  Nothing here re-throws and nothing here retries. A throwing consumer
  parks its cursor, and a retry inside a drain is a second run of a
  Routine nobody asked for.

  `at` is the FIRE TRANSITION's own instant, and it is what gets
  stamped — not this machine's clock. The stamp is what `already-fired?`
  compares a replay against, so the two must be read off one clock;
  the log's is the one both the engine and the database agree on."
  [eng adapter schedule-row text at]
  (let [url (some-> (get-in schedule-row [:data :fire_url]) str not-empty)
        token (some-> (get-in schedule-row [:data :fire_token]) str not-empty)]
    (when url
      (try
        (let [answer (fire-routine adapter url token text)]
          (try-act! eng schedule-row :fired
                    (cond-> {:last_fired_at (str (or (instant-of at) (now eng)))}
                      (some-> (:session-url answer) str not-empty)
                      (assoc :last_run_url (str (:session-url answer))))))
        (catch Exception e
          (let [{:keys [status retry-after]} (ex-data e)
                sentence (provider-note status retry-after)]
            (cond
              ;; a paused Routine, where the row can say so as a state
              (and (= 400 (some-> status long)) (= :live (:state schedule-row)))
              (try-act! eng schedule-row :pause nil)

              sentence (note! eng schedule-row sentence)
              :else (break! eng schedule-row e))))))))

;; ── the read-back (R-12.3) ──────────────────────────────────────────

(defn- stamp-seen!
  "The observed-unchanged discipline, borrowed whole from the mirror's
  read-through: the CHECK is a fact and `seen_at` must record it, but
  a transition per sweep per live seat would be audit noise nobody
  reads. So an unchanged read-back is a maintenance write —
  `store/update-data!`, no version bump, no transition — and only a
  CHANGED verdict takes the `observe` door."
  [eng schedule-row at]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                          (assoc (:data schedule-row) :seen_at (str at))
                          (:next-flip-at schedule-row)))))

(defn check-drift!
  "Read one live copy back and write what differs into `drift`.

  Report, never repair (R-12.3): the row is the truth, and what a
  difference MEANS is a person's to say — a sweep that silently
  pushed the row back over a hand-edited Routine would destroy the
  evidence it exists to collect.

  A verdict that has not changed since the last sweep — still clean,
  or still the same sentence — stamps `seen_at` and logs nothing;
  see `stamp-seen!`."
  [eng adapters schedule-row]
  (when (= :live (:state schedule-row))
    (let [seat-row (raw-row eng :seat (get-in schedule-row [:data :seat]))
          xid (some-> (get-in schedule-row [:data :external_id]) str not-empty)]
      (when (and seat-row xid)
        (try
          (let [copy (read-copy (adapter-for adapters schedule-row) xid)
                spec (copy-spec eng seat-row schedule-row)
                found (drift-of spec (:state schedule-row) copy)
                at (now eng)]
            (if (= found (get-in schedule-row [:data :drift]))
              (stamp-seen! eng schedule-row at)
              (act! eng (:id schedule-row) :observe
                    {:seen_at (str at) :drift found})))
          (catch Exception e (break! eng schedule-row e)))))))

(defn sweep-drift!
  "One drift pass: every live schedule read back. → the number checked."
  [eng]
  (let [adapters (adapters-of eng)]
    (reduce (fn [n row] (check-drift! eng adapters row) (inc n))
            0
            (rows-where eng :schedule {:state :live} 500))))

(def default-drift-interval-ms
  "Fifteen minutes (R-12.3's cadence), overridable per engine as
  `:schedule-drift-ms`."
  (* 15 60 1000))

(defn start-drift-sweeper!
  "The read-back's loop. Returns the handle `stop-drift-sweeper!`
  takes. ONE process per database should run it, and that is not
  decided here: the module's hook carries `:elected`."
  [eng {:keys [interval-ms] :or {interval-ms default-drift-interval-ms}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep-drift! eng)
                              (catch Exception e
                                (warn! "drift sweep failed: " (ex-message e))))
                         (recur))))
                   "waymark10-schedules-drift")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-drift-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)

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

(defn handle-transition!
  "One transition → the push it implies, or nothing.

  The doors it listens for are the seat's own, by action name
  (spec-seat R-4.4), plus the schedule's single human door:

      seat create           mint the row, stamp the seat, create the copy
      seat restate          push again (name, cadence, model)
      seat park             pause      seat unpark   resume
      seat merge, retire    delete
      seat fire             start the linked Routine's run (R-12.19)
      schedule restate      push again (the model a person restated)

  Everything else — including every transition this namespace itself
  writes — is ignored, which is what keeps the consumer from feeding
  itself.

  A recorded trade: EVERY seat `restate` pushes, where R-12.2 asks
  only a restate of the name or the cadence to. Telling them apart
  needs the last-pushed spec on the row, and the push is idempotent,
  so the cost of not knowing is one extra API call on a restate that
  changed only the charter — against a column that would have to stay
  true forever."
  [eng adapters t]
  (let [kind (:kind t)
        action (:action t)]
    (cond
      (= :seat kind)
      (let [births (seat-create-actions eng)
            seat-row (raw-row eng :seat (:resource-id t))]
        (cond
          ;; AN INTERACTIVE SEAT HAS NO SCHEDULE (R-10.8). Nothing
          ;; fires it, so no row is minted and nothing is pushed — and
          ;; a seat RESTATED into the mode ends the copy it already
          ;; had, rather than leaving a Routine firing an office
          ;; nobody may fire. `delete!` is idempotent at the adapter
          ;; and a row already ended is left alone.
          (seats/interactive-seat? seat-row)
          (when-some [row (schedule-for-seat eng (:resource-id t))]
            (delete! eng adapters row))

          (contains? births action)
          (when seat-row
            (push! eng adapters (ensure-schedule! eng seat-row)))

          (= :restate action)
          (when-some [row (schedule-for-seat eng (:resource-id t))]
            (push! eng adapters row))

          (= :park action)
          (when-some [row (schedule-for-seat eng (:resource-id t))]
            (pause! eng adapters row))

          (= :unpark action)
          (when-some [row (schedule-for-seat eng (:resource-id t))]
            (resume! eng adapters row))

          (contains? #{:merge :retire} action)
          (when-some [row (schedule-for-seat eng (:resource-id t))]
            (delete! eng adapters row))

          ;; THE FIRE GOES OUT AFTER THE COMMIT (R-12.19). The door
          ;; already refused what a door can refuse, so an unlinked row
          ;; — or no row at all — is silence here and not a second
          ;; refusal; and a replayed transition whose fire already went
          ;; out is skipped rather than fired twice.
          (= :fire action)
          (when-some [row (schedule-for-seat eng (:resource-id t))]
            (when-not (already-fired? row (:at t))
              (fire! eng (fire-adapter-of eng) row
                     (some-> (get-in t [:inputs :text]) str not-empty)
                     (:at t))))))

      (and (= :schedule kind) (= :restate action))
      (when-some [row (raw-row eng :schedule (:resource-id t))]
        (push! eng adapters row)))))

(defn consumer-fn
  "The consumer's function of one transition, with the adapters read
  once per registration. Public because a test drains it directly
  (`consumers/drain-consumer!`), which is how the framework's own
  consumer suite stays deterministic."
  [eng]
  (let [adapters (adapters-of eng)]
    (fn [t] (handle-transition! eng adapters t))))

(defn serving?
  "Does this engine serve the schedule kind? The module's `:when`, on
  the mirror module's precedent: a house that did not opt in starts no
  consumer and no drift sweep and pays nothing for the module."
  [eng]
  (contains? (inv/resources eng) :schedule))

(defn start-mirror!
  "Register the durable log consumer that mirrors seats out. Returns
  the running consumer; `stop-mirror!` ends it."
  ([eng] (start-mirror! eng {}))
  ([eng opts]
   (consumers/register-consumer! eng consumer-name (consumer-fn eng) opts)))

(defn stop-mirror! [running]
  (consumers/stop-consumer! running))

;; ── the scriptable twin ─────────────────────────────────────────────
;;
;; The tests' instrument, offline dev's default, and what the
;; declaration gate runs over so no check touches the network —
;; calendar10's FakeCalendar, resized to six operations. It is HERE
;; rather than in the test tree for the same reason that one is: the
;; fake is part of the seam's definition, and an adapter whose twin
;; lives somewhere else drifts from it.

(def ^:private fresh-scheduler
  {:copies {} :by-token {} :seq 0
   :creates 0 :updates 0 :pauses 0 :resumes 0 :deletes 0 :reads 0
   :down false})

(defn- reachable! [state]
  (when-some [down (:down @state)]
    (when down
      (throw (ex-info (if (string? down) down "the scheduler is unreachable")
                      {})))))

(defrecord FakeScheduler [state]
  ScheduleAdapter

  (create-copy [_ spec]
    (swap! state update :creates inc)
    (reachable! state)
    ;; the client token IS the dedupe: a replayed create answers the
    ;; copy it already made, which is what the consumer leans on
    (if-some [known (get-in @state [:by-token (:client-token spec)])]
      {:external-id known}
      (let [s (swap! state
                     (fn [s]
                       (let [n (inc (long (:seq s)))
                             x (str "fake-routine-" n)]
                         (-> s
                             (assoc :seq n :last x)
                             (assoc-in [:copies x]
                                       {:external-id x
                                        :name (:name spec)
                                        :cron (:cron spec)
                                        :model (:model spec)
                                        :prompt (:prompt spec)
                                        :enabled true})
                             (assoc-in [:by-token (:client-token spec)] x)))))]
        {:external-id (:last s)})))

  (update-copy [_ x spec]
    (swap! state update :updates inc)
    (reachable! state)
    (when-not (get-in @state [:copies x])
      (throw (ex-info (str x " is not a routine here") {:status 404})))
    ;; name, cron and model only — the prompt stands, which is how the
    ;; fake proves R-12.3 rather than assuming it
    (swap! state update-in [:copies x]
           #(assoc % :name (:name spec) :cron (:cron spec) :model (:model spec)))
    nil)

  (pause-copy [_ x]
    (swap! state update :pauses inc)
    (reachable! state)
    (swap! state assoc-in [:copies x :enabled] false)
    nil)

  (resume-copy [_ x]
    (swap! state update :resumes inc)
    (reachable! state)
    (swap! state assoc-in [:copies x :enabled] true)
    nil)

  (delete-copy [_ x]
    (swap! state update :deletes inc)
    (reachable! state)
    ;; deleting what is already gone succeeds — the consumer replays
    (swap! state update :copies dissoc x)
    nil)

  (read-copy [_ x]
    (swap! state update :reads inc)
    (reachable! state)
    (or (get-in @state [:copies x])
        (throw (ex-info (str x " is not a routine here") {:status 404})))))

(defn fake-scheduler
  "A scriptable scheduler with nothing on it."
  []
  (->FakeScheduler (atom fresh-scheduler)))

(defn copies
  "external id → the copy, as the fake holds it."
  [fake]
  (:copies @(:state fake)))

(defn copy
  "One copy, or nil."
  [fake x]
  (get-in @(:state fake) [:copies x]))

(defn counts
  "{:creates :updates :pauses :resumes :deletes :reads} — what the
  adapter was actually asked to do."
  [fake]
  (select-keys @(:state fake)
               [:creates :updates :pauses :resumes :deletes :reads]))

(defn down!
  "Script the wire: truthy makes every operation throw (pass a string
  for the exact sentence); false restores it."
  [fake failing]
  (swap! (:state fake) assoc :down failing))

(defn tamper!
  "Change a copy behind the engine's back — somebody editing the
  Routine by hand, which is the thing R-12.0 forbids and R-12.3 asks
  the read-back to catch."
  [fake x patch]
  (swap! (:state fake) update-in [:copies x] merge patch))

(defn reset-scheduler! [fake]
  (reset! (:state fake) fresh-scheduler))

;; ── the fire's scriptable twin ──────────────────────────────────────
;;
;; The same reason the scheduler's twin is here: the fake is part of
;; the seam's definition. It records every fire in order, so a test
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
