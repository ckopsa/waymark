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

  ── the engine opt a deployment owes ───────────────────────────────

  `adapters-of` reads `(:schedule-adapters eng)` first and falls back
  to the environment. `server/engine.clj` whitelists its opts, so
  until `:schedule-adapters` is added there the env IS the deployment
  path (which is R-12.11's path) and the opt is the tests' seam."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
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
     [:maybe [:string {:max 280}]]]]
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
     :display {:label "Restate the model" :order 1}}}
   :deviations
   ["The schedule is NOT declared through server/mirror, though R-12.0 names the calendar as the precedent. Three reasons: mirror's authority points inward (a pull wins; R-12.3 wants a read-back that reports and never repairs), mirror refuses a kind that declares its own :states (R-12.1 names four), and MirrorAdapter has no pause, resume or delete (calendar10 had to hang delete-event! off the side of the protocol). The seam is ScheduleAdapter instead, and the bookkeeping posture — hidden system doors over ordinary data fields — is borrowed from mirror whole."
    "R-12.1 lists four states; this kind has five. `ended` is where a retired or merged seat's schedule lands once the copy is deleted. The alternative was returning the row to `pending`, which means \"no copy yet\" and invites the next push to make one."
    "R-12.1's field table does not list `note`, but its sentence for the `broken` state says \"the note says why\" and R-12.11 asks the kind to serve a broken provider \"with a note saying so\". So `note` is a field, engine-written, and the `fail` door records it in the log as well (the log's inputs column, R-4.7's spelling)."
    "R-12.2 calls the push a post-commit effect at the wire boundary. It is a durable log consumer here, which is what the brief asked to be built and the more honest of the two under a crash: the log is the record, and an effect that dies takes its push with it. Wave two may move it (waymark-442.14)."
    "R-12.6's ceiling is not pushed to the provider. The Routines API is not pinned in this repository, and R-12.6 names the fallback itself — the walk's `rows_per_firing` caps the firing. One field on the create payload when the real API is known."]})

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

(defn- break! [eng row ^Exception e]
  (let [note (clip (or (not-empty (str (ex-message e)))
                       "The adapter could not reach the provider."))]
    (when (not= note (get-in row [:data :note]))
      (act! eng (:id row) :fail {:note note}))
    nil))

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
  other seat."
  [eng adapters schedule-row]
  (let [seat-id (get-in schedule-row [:data :seat])
        seat-row (raw-row eng :seat seat-id)]
    (cond
      (= :ended (:state schedule-row)) nil

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
            (act! eng (:id schedule-row) :claim
                  {:external_id xid :pushed_at (now eng)}))
          (catch Exception e
            (break! eng schedule-row e)))))))

(defn pause!
  "Park the seat: the copy stops firing and keeps its settings. A row
  that is already paused, ended or never pushed is left alone — the
  transition would 409 and park the drain."
  [eng adapters schedule-row]
  (when-some [xid (some-> (get-in schedule-row [:data :external_id]) str not-empty)]
    (when (= :live (:state schedule-row))
      (try
        (pause-copy (adapter-for adapters schedule-row) xid)
        (act! eng (:id schedule-row) :pause nil)
        (catch Exception e (break! eng schedule-row e))))))

(defn resume!
  "Unpark the seat. A row that is not paused is left alone; a row that
  is BROKEN goes back through `push!`, because the copy may never have
  been made and resuming a copy that does not exist is not a repair."
  [eng adapters schedule-row]
  (case (:state schedule-row)
    :paused (if-some [xid (some-> (get-in schedule-row [:data :external_id])
                                  str not-empty)]
              (try
                (resume-copy (adapter-for adapters schedule-row) xid)
                (act! eng (:id schedule-row) :resume nil)
                (catch Exception e (break! eng schedule-row e)))
              (push! eng adapters schedule-row))
    :broken (push! eng adapters schedule-row)
    nil))

(defn delete!
  "Retire or merge the seat: the copy is removed and the row ends. The
  delete is idempotent at the adapter (a copy already gone succeeds),
  so a replay reaches `end` and stops there."
  [eng adapters schedule-row]
  (when-not (= :ended (:state schedule-row))
    (let [xid (some-> (get-in schedule-row [:data :external_id]) str not-empty)]
      (try
        (when xid (delete-copy (adapter-for adapters schedule-row) xid))
        (act! eng (:id schedule-row) :end nil)
        (catch Exception e (break! eng schedule-row e))))))

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
                    {:seen_at at :drift found})))
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
      (let [births (seat-create-actions eng)]
        (cond
          (contains? births action)
          (when-some [seat-row (raw-row eng :seat (:resource-id t))]
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
            (delete! eng adapters row))))

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
