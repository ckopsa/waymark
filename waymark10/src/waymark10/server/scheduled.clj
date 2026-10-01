(ns waymark10.server.scheduled
  "The scheduled action (docs/spec-scheduled-actions.md, children 1a,
  1b, 1c and 2): a call stored for a time, as a kind of its own.

  THIS NAMESPACE IS THE KIND, ITS SCHEDULING CHECK, ITS RUN AND ITS
  CLOCK. The create validates, checks and stores. `start!` claims a row
  and `run!` carries it out; `sweep-due!` is the clock that calls them
  (R-5), on a loop of its own. `start`, `land`, `skip` and `fail` are
  the engine's own hand, and no hand at the wire walks them.

  CHECKED AGAIN AT THE RUN (R-3.3). The claim, then the validity rule
  the row chose (R-2), then the confirm sentence read again, then a dry
  run, then the call under the key `scheduled_action:<id>`, so a
  repeated run lands once. A refusal a rehearsal could have given is a
  skip. `failed` is kept for what a rehearsal cannot see.

  WHAT WOULD BE REFUSED NOW IS REFUSED NOW (R-3.1). At the create the
  target door is rehearsed as the scheduler, under the grant it wears,
  through the ctx `:rehearse` door: the invoke door's own dry run, or
  the create rehearsal in full. The door's own refusal is thrown as
  the door wrote it and no row is written. Two refusals are passed
  over, each only when the scheduler names it (R-3.2): a state
  (`expect_state`) and guards (`expect_refusals`). Neither loosens the
  run.

  NOBODY SCHEDULES AS SOMEBODY ELSE (R-4.2). `scheduler`, `acts_as`
  and `grant` are stamped at birth, and the closed create model
  refuses a body that names one. At the run the member row is read
  again, and the grant by its id: a grant that is gone or that no
  longer admits the call skips the row, and so does a seat that is not
  open. The run's transition counts on no sitting.

  THE ENGINE NEVER GUESSES UTC (R-7.2). `run_at` is RFC 3339 with an
  offset, or a local time read in the `zone` the body names, else in
  the zone on the scheduler's member row or its person's. From then
  on it is an instant in whole minutes."
  (:require [clojure.string :as str]
            [waymark10.confirm :as confirm]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.nio.charset StandardCharsets)
           (java.time DateTimeException Instant LocalDateTime OffsetDateTime
                      ZoneId ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(def kind :scheduled_action)

(def engine-actor
  "The system actor that starts a scheduled action and writes its ending."
  (t/principal {:id "waymark10-scheduled-actions" :type :system
                :display "Scheduled actions"}))

;; ── the limits (R-1) ────────────────────────────────────────────────

(def min-ahead-seconds "`run_at` is at least this far ahead." 60)
(def max-ahead-days "…and at most this far." 366)

(def open-limit
  "The most rows one scheduler holds that have not run: what stops a
  seat from spending tomorrow's budget today (R-4.2)."
  100)

(def open-states "The states that count against it; child 4 adds `proposed`." [:scheduled])

(def input-cap-bytes "The ceiling on a stored `input`: a held call's 16 KB." 16384)

;; ── the time (R-7.2) ────────────────────────────────────────────────

(defn- zone-id ^ZoneId [s]
  (when-some [s (some-> s str str/trim not-empty)]
    (try (ZoneId/of ^String s) (catch DateTimeException _ nil))))

(defn- first-zone ^ZoneId [zones]
  (some zone-id zones))

(defn- offset-time ^OffsetDateTime [^String s]
  (try (OffsetDateTime/parse s) (catch DateTimeException _ nil)))

(defn- local-time ^LocalDateTime [^String s]
  (try (LocalDateTime/parse s) (catch DateTimeException _ nil)))

(defn- whole-minute ^Instant [^Instant i]
  (.truncatedTo i ChronoUnit/MINUTES))

(defn resolve-time
  "`run_at` and `zone` as the scheduler wrote them → {:instant :zone},
  or {:problem <a sentence>}. `fallbacks` are the zones a local time
  is read in when the body names none, nearest first."
  [run-at zone fallbacks]
  (let [text (str/trim (str run-at))
        named (some-> zone str str/trim not-empty)
        z (zone-id named)]
    (if (and named (nil? z))
      {:problem (str "`zone` is \"" named "\", which is not an IANA time zone. Name one like America/Denver.")}
      (if-some [^OffsetDateTime odt (offset-time text)]
        {:instant (whole-minute (.toInstant odt))
         :zone (.getId ^ZoneId (or z (first-zone fallbacks) (.getOffset odt)))}
        (if-some [^LocalDateTime ldt (local-time text)]
          (if-some [^ZoneId z (or z (first-zone fallbacks))]
            (if (empty? (.getValidOffsets (.getRules z) ldt))
              {:problem (str "`run_at` is " text " in " (.getId z) ", a local time that does not exist: the clocks skip it that day. Name a time that is on the clock.")}
              ;; a time that occurs twice takes the earlier offset
              {:instant (whole-minute (.toInstant (ZonedDateTime/ofLocal ldt z nil)))
               :zone (.getId z)})
            {:problem (str "`run_at` is " text ", a local time with no zone, and your member row names none. Name a `zone`, or write the time with its offset; the engine never guesses UTC.")})
          {:problem (str "`run_at` is \"" text "\". Write RFC 3339 with an offset (2026-10-02T08:30:00-06:00), or a local date and time (2026-10-02T08:30) beside a `zone`.")})))))

(defn- member-zones
  "The zones the scheduler's own rows name, nearest first: the member
  the principal is, then the person it acts for. Read through ctx, as
  grants/waits-on reads the same two rows."
  [ctx]
  (when (and (:read ctx) (:find ctx))
    (let [named (fn [who]
                  (when-some [who (some-> who str not-empty)]
                    (or ((:read ctx) :member who)
                        (first ((:find ctx) :member {:subject who} {:limit 1})))))
          p (:principal ctx)
          m (named (:id p))
          person (or (some-> (get-in m [:data :acts_for]) str not-empty)
                     (some-> (:acts-for p) str not-empty))]
      (keep #(some-> (get-in % [:data :zone]) str not-empty)
            [m (when person (named person))]))))

(defn- time-of
  "The instant one door's input names. A reschedule that names no zone
  reads the time in the zone the row already shows."
  [row inp ctx]
  (resolve-time (:run_at inp) (:zone inp)
                (cons (get-in row [:data :zone]) (member-zones ctx))))

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard the-target-is-an-engine-door
  {:vars [:problem]
   :explain "A scheduled action names one door of this engine: {problem}"}
  [_row inp _ctx]
  (let [{:keys [kind action tool]} (:target inp)
        blank? #(str/blank? (str %))]
    (cond
      (nil? (:target inp)) (t/allow)

      (not (blank? tool))
      (t/deny {:vars {:problem "a power tool target is child 4 of docs/spec-scheduled-actions.md (approval at scheduling, and power targets), which is not built yet. Name a `kind` and an `action`."}})

      (or (blank? kind) (blank? action))
      (t/deny {:vars {:problem "`target` names a `kind` and an `action`, and the row's `id` unless the action creates one."}})

      ;; whether the door is there to take is the scheduling check's (1b)
      :else (t/allow))))

(g/defguard the-rule-is-built
  {:explain "The `conditions` rule is child 5 of docs/spec-scheduled-actions.md (conditions), which is not built yet. Schedule this under `strict` or `state`, and leave `conditions` out."}
  [_row inp _ctx]
  (if (or (= "conditions" (:validity inp)) (seq (:conditions inp)))
    (t/deny)
    (t/allow)))

(g/defguard the-input-fits
  {:vars [:bytes :limit]
   :explain "The stored input is {bytes} bytes, and a scheduled action keeps at most {limit}. Schedule a smaller call."}
  [_row inp _ctx]
  (let [n (alength (.getBytes ^String (pr-str (:input inp)) StandardCharsets/UTF_8))]
    (if (< (long input-cap-bytes) n)
      (t/deny {:vars {:bytes (str n) :limit (str input-cap-bytes)}})
      (t/allow))))

(g/defguard the-time-names-one-instant
  {:reads [:principal :storage]
   :vars [:problem]
   :explain "A scheduled action runs at one instant: {problem}"}
  [row inp ctx]
  (if-some [problem (when (some? (:run_at inp))
                      (:problem (time-of row inp ctx)))]
    (t/deny {:vars {:problem problem}})
    (t/allow)))

(g/defguard the-time-is-in-range
  {:reads [:now :principal :storage]
   :vars [:problem]
   :explain "A scheduled action runs at least one minute and at most 366 days ahead: {problem}"}
  [row inp ctx]
  (let [^Instant at (when (some? (:run_at inp))
                      (:instant (time-of row inp ctx)))
        ^Instant now (:now ctx)]
    (cond
      ;; a time that does not read is the-time-names-one-instant's
      (or (nil? at) (nil? now)) (t/allow)

      (.isBefore at (.plusSeconds now (long min-ahead-seconds)))
      (t/deny {:vars {:problem (str "`run_at` is " at ", which is less than one minute from now.")}})

      (.isAfter at (.plus now (long max-ahead-days) ChronoUnit/DAYS))
      (t/deny {:vars {:problem (str "`run_at` is " at ", which is more than 366 days from now.")}})

      :else (t/allow))))

(g/defguard the-scheduler-has-room
  {:reads [:principal :storage]
   :vars [:limit]
   :explain "One scheduler holds at most {limit} scheduled actions that have not run, and you hold that many. Cancel one, or wait for one to run."}
  [_row _inp ctx]
  (let [who (some-> (get-in ctx [:principal :id]) str not-empty)
        rows (:find ctx)
        limit (long open-limit)]
    (if (and who rows
             (<= limit (count (mapcat #(rows kind {:scheduler who :state %}
                                             {:limit (inc limit)})
                                      open-states))))
      (t/deny {:vars {:limit (str limit)}})
      (t/allow))))

(g/defguard the-scheduler-moves-it
  {:reads [:principal]
   :open "The wall is about who: whoever scheduled this cancels or moves it, and no field of this door makes anyone else the scheduler."
   :explain "Only whoever scheduled this action, or the person they act for, cancels or reschedules it."}
  [row _inp ctx]
  (let [who (str (:id (:principal ctx)))
        person (some-> (get-in row [:data :acts_as :acts_for]) str not-empty)]
    (if (or (= who (str (get-in row [:data :scheduler])))
            (= who person))
      (t/allow)
      (t/deny))))

(g/defguard the-engine-runs-it
  {:reads [:principal]
   :hide true
   :explain "The engine starts a scheduled action at its time and writes how it ended; no hand at the wire does."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

;; ── the scheduling check (R-3.1, R-3.2) ─────────────────────────────

(defn- door-of
  "The door a target names, read from this engine's registry under the
  grant the scheduler wears: `{:rdef :kind :action :id :defn}`, with no
  `:id` and no `:defn` for the kind's create, or `{:problem}`. A door
  the grant does not admit reads as a door that is not there."
  [target ctx]
  (let [kind-name (some-> (:kind target) str not-empty)
        action (some-> (:action target) str not-empty keyword)
        id (some-> (:id target) str not-empty)
        rdef (when-some [rdef-of (:rdef-of ctx)]
               (when kind-name (rdef-of kind-name)))
        kind (:kind rdef)
        grant (:grant ctx)
        admits? (fn [k & args] (if-some [f (get grant k)] (apply f args) true))
        defn' (when-some [d (when action (get-in rdef [:actions action]))]
                (when-not (:bulk d) (assoc d :name action)))
        create? (and action
                     (contains? (set (map name (:create-action-names rdef)))
                                (name action)))]
    (cond
      (or (nil? rdef) (nil? action)
          (not (admits? :action? kind action))
          (and id (not (admits? :row? kind id))))
      {:problem (str "there is no door `" (:action target) "` on `" (:kind target) "`"
                     (when id (str " for the row `" id "`"))
                     " that you may schedule.")}

      (and id defn') {:rdef rdef :kind kind :action action :id id :defn defn'}

      id {:problem (str "`" kind-name "` has no action `" (name action) "` that acts on a row.")}

      create? {:rdef rdef :kind kind :action action}

      defn' {:problem (str "`" (name action) "` acts on a row, so `target` names the row's `id`.")}

      :else {:problem (str "`" kind-name "` has no action `" (name action) "`.")})))

(defn- sentence-of
  "A confirm door's consequence sentence as the row states it in
  `state`: the entry the envelope renders, read through the one
  accessor every confirm gate shares."
  [defn' state]
  (let [{:keys [safety display]} defn'
        consequence (:consequence safety)
        said (if (map? consequence) (get consequence state) consequence)]
    (confirm/consequence-of
     {:display (cond-> display
                 (and said (nil? (:description display)))
                 (assoc :description said))})))

(defn- confirm-problem
  "A confirm door demands its sentence at scheduling, as it does on an
  invoke (R-3.1). The sentence is the one the row states in `state`."
  [defn' state inp]
  (when (get-in defn' [:safety :confirm])
    (let [sentence (sentence-of defn' state)]
      (when (not= sentence (:acknowledge inp))
        (str "`" (name (:name defn')) "` is a confirm door, and it takes its consequence sentence at scheduling as it does on an invoke. Send `acknowledge` exactly as written: "
             sentence)))))

(defn- input-problem
  "What the door's input schema refuses in a stored input, as one
  sentence, or nil. Read under `expect_state`, where the door itself
  cannot be rehearsed."
  [defn' input]
  (let [model (:input defn')
        errors (if model
                 (schema/closed-errors
                  model (schema/apply-defaults model (schema/decode model (or input {}))))
                 (when (seq input) input))
        named #(if (instance? clojure.lang.Named %) (name %) (str %))]
    (when (seq errors)
      (str "the input does not fit `" (name (:name defn')) "`; look at "
           (str/join ", " (map #(str "`" (named (key %)) "`") errors)) "."))))

(defn- rehearse!
  "R-3.1: the call, rehearsed as the scheduler under the grant it wears.
  The door's own refusal is thrown as the door wrote it, unless it is a
  guard the scheduler named in `expect_refusals` (R-3.2)."
  [{:keys [kind action id]} inp ctx]
  (let [expected (set (:expect_refusals inp))
        accepted (into #{} (map keyword) (:acknowledge_warnings inp))]
    (try
      ((:rehearse ctx) kind id action (or (:input inp) {}) {:acknowledged accepted})
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [guard] :as d} (ex-data e)]
          (when-not (and (= :guard-refused (:waymark10/problem d))
                         guard
                         (contains? expected (name guard)))
            (throw e)))))))

(defn- refused-now
  "The scheduling check. Answers the sentence of a refusal this kind
  writes, or nil; throws the door's own refusal."
  [inp ctx]
  (let [{:keys [problem kind action id] defn' :defn :as door} (door-of (:target inp) ctx)
        row (when (and id (nil? problem))
              (when-some [read (:read ctx)] (read kind id)))
        named (some-> (:expect_state inp) str not-empty keyword)
        ;; R-3.2: a state is expected only when the row is not in it now
        expect (when (and named (not= named (:state row))) named)]
    (cond
      problem problem

      (and expect (nil? id))
      "a create has no row, so there is no state to expect. Leave `expect_state` out."

      ;; no guard is judged against a row in the wrong state: the schema
      ;; and the declared from-state are all that can be read honestly
      (and expect row)
      (cond
        (= "strict" (:validity inp))
        (str "`strict` pins the row's version, and a row that has not reached `"
             (name expect) "` has no version to pin. Schedule this under `state`.")

        (not (contains? (:from defn') expect))
        (str "`" (name action) "` is not declared from `" (name expect) "`; it leaves "
             (str/join ", " (map #(str "`" (name %) "`") (sort (:from defn')))) ".")

        :else (or (input-problem defn' (:input inp))
                  (confirm-problem defn' expect inp)))

      :else (do (rehearse! door inp ctx)
                (when row (confirm-problem defn' (:state row) inp))))))

(g/defguard the-door-would-take-it
  {:reads [:principal :grant :storage]
   :vars [:problem]
   :explain "A scheduled action is checked against its door when it is scheduled: {problem}"}
  [_row inp ctx]
  (if-some [problem (when (and (:rehearse ctx) (map? (:target inp)))
                      (refused-now inp ctx))]
    (t/deny {:vars {:problem problem}})
    (t/allow)))

(defn- snapshot-of
  "What the target was when this was scheduled (R-1): the row's version,
  state and law revision. Under `expect_state` it is the expected state
  and no version, because a row that has not got there has none to pin
  (R-3.2). A create has no row, and keeps the law a birth would be
  stamped by now (R-2.4)."
  [data ctx]
  (let [{:keys [problem kind id]} (door-of (:target data) ctx)
        row (when (and id (nil? problem))
              (when-some [read (:read ctx)] (read kind id)))
        at (some-> (:state row) name)
        named (some-> (:expect_state data) str not-empty)
        expect (when (and row (not= named at)) named)
        law (cond
              row (:law-revision row)
              (and (nil? id) (nil? problem)) (when-some [law-of (:law-of ctx)]
                                               (law-of kind)))]
    (not-empty
     (into {} (filter (comp some? val))
           {:version (when-not expect (:version row))
            :state (or expect at)
            :law_revision (some-> law str)}))))

;; ── handlers ────────────────────────────────────────────────────────

(defn- born
  "The birth stamps, read from the principal and never from the body:
  who scheduled it, who the run runs as, and the id of the grant it
  wore. `run_at` becomes the instant the body named, in whole minutes,
  and `zone` the zone it is shown in. `snapshot` is the target as the
  scheduling check met it."
  [row ctx]
  (let [p (:principal ctx)
        {:keys [instant zone]} (time-of nil (:data row) ctx)
        acts-for (some-> (:acts-for p) str not-empty)
        grant (some-> (get-in ctx [:grant :id]) str not-empty)
        snapshot (snapshot-of (:data row) ctx)]
    (update row :data
            #(cond-> (assoc %
                            :scheduler (str (:id p))
                            :acts_as (cond-> {:id (str (:id p))
                                              :type (name (or (:type p) :human))}
                                       acts-for (assoc :acts_for acts-for))
                            :run_at instant
                            :zone zone)
               grant (assoc :grant grant)
               snapshot (assoc :snapshot snapshot)))))

(defhandler move-time [row inp ctx]
  (let [{:keys [instant zone]} (time-of row inp ctx)]
    (update row :data assoc :run_at instant :zone zone)))

(defhandler record-start [row _inp ctx]
  (assoc-in row [:data :ran_at] (:now ctx)))

(defhandler record-ending [row inp _ctx]
  (update row :data merge
          (into {} (filter (comp some? val))
                (select-keys inp [:outcome :outcome_why]))))

;; ── the run (R-2, R-3.3) ────────────────────────────────────────────

(defn- registry-ctx
  "What `door-of` reads, outside any write: this engine's registry and
  no grant. The run lays the grant it read by id over it (`leash-of`)."
  [eng]
  {:rdef-of (fn [token]
              (let [rs (inv/resources eng)
                    t (name token)]
                (or (get rs (keyword t))
                    (some (fn [[_ r]] (when (= t (:plural r)) r)) rs))))})

(defn- stored-row
  "One row as it stands now, decoded, or nil."
  [eng kind id]
  (let [st (:storage eng)]
    (when-some [rdef (get (inv/resources eng) kind)]
      (store/with-tx st
        (fn [tx]
          (some->> (store/load-row st tx kind (str id) {})
                   (inv/decode-row rdef)))))))

(defn- said
  "A refusal's own sentence."
  [^Throwable e]
  (str (or (:detail (ex-data e)) (ex-message e))))

(defn- call-of [door]
  (str "`" (name (:action door)) "` on " (name (:kind door))))

(defn- model-at-scheduling
  "The model the scheduling session declared, read off the row's first
  transition, or nil. A run has no session to declare one, and a seat
  held for named models serves nothing to a hand that declares none."
  [eng id]
  (let [st (:storage eng)]
    (some-> (store/with-tx st
              (fn [tx]
                (first (store/transitions st tx
                                          {:kind kind :resource-id (str id)}
                                          {:limit 1}))))
            :actor :model str not-empty)))

(defn- runner-of
  "Who the run runs as (R-4.1): the scheduler, built now from its member
  row, roles read now and not carried. `{:principal}`, or `{:why}` for
  a scheduler who is gone or whom the gate refuses. The principal
  carries `:scheduled`, this row's id, which the transition's actor
  keeps (invoke/actor-map): the hand is the scheduler's, and the log
  says the clock moved it (R-4.2)."
  [eng row]
  (try
    (if-some [p (members/principal-for eng (get-in row [:data :acts_as :id]))]
      (let [model (model-at-scheduling eng (:id row))]
        {:principal (cond-> (assoc p :scheduled (str (:id row)))
                      model (assoc :model model))})
      {:why "Whoever scheduled this is no longer a member here."})
    (catch clojure.lang.ExceptionInfo e
      {:why (said e)})))

(def ^:private grant-gone
  "The grant this was scheduled under no longer admits it.")

(defn- leash-of
  "R-4.2: the grant the row was scheduled under, read by its id as it
  stands now and never as it stood. `{:grant}` is the guard's-eye view
  the call is judged under, wider or narrower than the day it was
  cited. `{:why}` skips the row: a grant revoked, expired or narrowed
  past this call, or a seat that is not open. nil for a row scheduled
  under no grant.

  THE VIEW CARRIES NO ID. A sitting's transitions are the log rows
  whose actor names its grant, so a run that stamped the grant would
  count on whatever sitting is open at the time. The row keeps the
  grant's id, and the actor carries `scheduled`."
  [eng data principal]
  (when-some [gid (some-> (:grant data) str not-empty)]
    (let [vis (when (= :active (:standing (grants/grant-standing eng gid)))
                (grants/visibility eng gid principal))
          wall (get-in vis [:seat :reason])
          view (some-> (:grant vis) (dissoc :id))
          door (when view
                 (door-of (:target data) (assoc (registry-ctx eng) :grant view)))]
      (cond
        (= "seat_not_active" wall)
        {:why "The seat this was scheduled from is not open, and a seat that is not open does nothing."}

        wall
        {:why (str "The seat this was scheduled from serves nothing now (" wall ").")}

        (or (nil? view) (:problem door))
        {:why grant-gone}

        :else {:grant view}))))

(defn- stale
  "The validity rule the row chose, and the confirm gate read again,
  against the target as it is now: the sentence that skips the run, or
  nil. `strict` is the snapshot's version (R-2.1). `state` is the
  snapshot's state, or the expected one, and a door still declared from
  it (R-2.2). A create has no row: `strict` pins the law a birth is
  stamped by (R-2.4). A row that is gone and a row the runner cannot
  see are one sentence (R-2)."
  [eng {:keys [rdef kind action id] defn' :defn} data]
  (let [{:keys [version state law_revision]} (:snapshot data)
        strict? (= "strict" (:validity data))
        label (name kind)]
    (if id
      (let [row (stored-row eng kind id)
            at (some-> (:state row) name)]
        (cond
          (nil? row)
          (str "The " label " is gone.")

          (and strict? (not= version (:version row)))
          (str "The " label " changed since this was scheduled (version "
               version ", now " (:version row) ").")

          (and state (not= state at))
          (str "The " label " is `" at "`, and this runs only while it is `" state "`.")

          (not (contains? (:from defn') (:state row)))
          (str "`" (name action) "` is not a door of a " label " that is `" at "`.")

          ;; R-3.3: the scheduler agreed to a sentence, not to a verb
          (and (get-in defn' [:safety :confirm])
               (not= (sentence-of defn' (:state row)) (:acknowledge data)))
          "The consequence changed since this was acknowledged."))
      (when strict?
        (let [law (some-> (inv/create-law-revision eng rdef kind) str)]
          (when (not= law_revision law)
            (str "The law of " label " changed since this was scheduled (revision "
                 law_revision ", now " law ").")))))))

(defn- attempt
  "The call itself, as the runner: the door's invoke, or the kind's
  create when the target names no row."
  [eng {:keys [kind action id]} data opts]
  (let [body (or (:input data) {})]
    (if id
      (inv/invoke! eng kind id action body opts)
      (inv/create! eng kind body opts))))

(defn- carry-out
  "R-3.3: a dry run, then the invoke under the derived key. They are
  two transactions and the row may move between them, so the invoke's
  own judgment is the one that counts: `{:end :land :res}`, `{:end
  :skip :why}` for a refusal a rehearsal could have given, `{:end :fail
  :why}` for what it could not see."
  [eng id {target :id :as door} data principal grant]
  (let [version (get-in data [:snapshot :version])
        opts (cond-> {:principal principal
                      :acknowledged (into #{} (map keyword) (:acknowledge_warnings data))}
               grant (assoc :grant grant)
               ;; R-2.1: strict sends the snapshot's etag as If-Match
               (and target version (= "strict" (:validity data)))
               (assoc :if-match (inv/etag (:kind door) target version)))]
    (try
      (let [warned (:warnings (attempt eng door data (assoc opts :dry-run true)))]
        (if (seq warned)
          {:end :skip
           :why (str (call-of door) " met a warning nobody accepted: "
                     (str/join ", " (keep #(some-> (:name %) name) warned)) ".")}
          {:end :land
           :res (attempt eng door data
                         (assoc opts :idempotency-key (str "scheduled_action:" id)))}))
      (catch clojure.lang.ExceptionInfo e
        (if (:waymark10/problem (ex-data e))
          {:end :skip :why (str (call-of door) " was refused: " (said e))}
          {:end :fail :why (str (call-of door) " failed: " (said e))}))
      (catch Exception e
        {:end :fail :why (str (call-of door) " failed: " (said e))}))))

(defn- outcome-of
  "`{kind, action, id, state}` of the row the call wrote. A replayed
  call answers no row, so the row is read again."
  [eng {:keys [kind action id]} res]
  (let [written (:row res)
        id (or (some-> (:id written) str not-empty) id)
        now (or written (when id (stored-row eng kind id)))]
    (cond-> {:kind (name kind) :action (name action)}
      id (assoc :id id)
      (:state now) (assoc :state (name (:state now))))))

(defn- end!
  "Write one ending with the engine's own hand, and answer its state."
  [eng id action why outcome]
  (let [why (str why)
        why (if (< 240 (count why)) (str (subs why 0 239) "…") why)]
    (inv/invoke! eng kind (str id) action
                 (cond-> {:outcome_why why}
                   outcome (assoc :outcome outcome))
                 {:principal engine-actor})
    ({:land :done :skip :skipped :fail :failed} action)))

(defn start!
  "The claim: move a `scheduled` row to `running` with the engine's own
  hand. Answers the row, or nil when this call did not claim it: the
  row is not `scheduled`, or another caller's `start` is what a replay
  answered. One caller holds a row."
  [eng id]
  (try
    (let [res (inv/invoke! eng kind (str id) :start {} {:principal engine-actor})]
      (when-not (:replayed? res)
        (:row res)))
    (catch clojure.lang.ExceptionInfo e
      (when-not (#{:wrong-state :not-found} (:waymark10/problem (ex-data e)))
        (throw e)))))

(defn- landed
  "What the call wrote, when it already landed under the row's derived
  key: `{kind, action, id, state}` from the stored answer, or nil. A
  row a stopped engine left `running` may have made its call, and the
  validity rule would then read the row the call itself moved, so the
  run asks here before it reads that rule (R-5.4)."
  [eng id {:keys [rdef kind action] target :id}]
  (let [st (:storage eng)
        hit (store/with-tx st
              (fn [tx]
                (store/idempotency-lookup st tx (str "scheduled_action:" id) kind)))
        did (some-> (:action hit) name)
        births (into #{} (map name) (:create-action-names rdef))]
    (when (and did (or (= did (name action))
                       (and (nil? target) (contains? births did))))
      (let [answer (:response hit)
            doc (cond
                  (map? answer) answer
                  (string? answer) (try (wire/read-json answer)
                                        (catch Exception _ nil)))
            doc (when (map? doc) doc)
            ;; an envelope names its row by `self`, never by a field
            self (some-> (:self doc) str (str/split #"/") last not-empty)
            written (or target (some-> (:id doc) str not-empty) self)
            state (or (some-> (:state doc) name not-empty)
                      (some-> (when written (stored-row eng kind written))
                              :state name))]
        (cond-> {:kind (name kind) :action (name action)}
          written (assoc :id written)
          state (assoc :state state))))))

(defn run!
  "The run of a row `start!` claimed (R-3.3): who it runs as, the grant
  read again by its id, the validity rule, the confirm sentence read
  again, a dry run, then the call under the key `scheduled_action:<id>`.
  A call that already landed under that key ends the row from its
  stored answer and nothing is judged again. Answers the ending,
  `:done`, `:skipped` or `:failed`. A row that is not `running` is left
  as it is and answers nil."
  [eng id]
  (let [row (stored-row eng kind id)]
    (when (= :running (:state row))
      (let [data (:data row)
            door (door-of (:target data) (registry-ctx eng))
            before (when-not (:problem door) (landed eng id door))
            {:keys [principal why]} (when-not before (runner-of eng row))
            leash (when (and principal (nil? (:problem door)))
                    (leash-of eng data principal))
            why (when-not before
                  (or why
                      (some->> (:problem door) (str "The door is gone: "))
                      (:why leash)
                      (stale eng door data)))
            {:keys [end why res]} (cond
                                    before {:end :land}
                                    why {:end :skip :why why}
                                    :else (carry-out eng id door data principal
                                                     (:grant leash)))]
        (end! eng id end
              (or why (str "Ran " (call-of door) " as scheduled."))
              (or before (when (= :land end) (outcome-of eng door res))))))))

;; ── the clock (R-5) ─────────────────────────────────────────────────

(def sweep-cap
  "The most rows a pass takes of each sort. The rest wait one pass."
  200)

(def stuck-seconds
  "A row `running` for longer than this has no live runner (R-5.4)."
  300)

(def default-sweep-ms
  "Thirty seconds. `run_at` is whole minutes, so a row runs in the pass
  after its minute begins; overridable per engine as `[:services
  :scheduled-actions :sweep-ms]`."
  30000)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 scheduled actions: " parts))))

(defn- rows-where
  "Decoded rows in `state` whose instant `field` is `op` `at`, in the
  order they were scheduled, up to the cap."
  [eng state field op ^Instant at]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (store/with-tx st
      (fn [tx]
        (mapv #(inv/decode-row rdef %)
              (store/search-rows st tx kind
                                 [{:target :state :op := :value (name state)}
                                  {:target :data :field field :cast "timestamptz"
                                   :op op :value (str at)}]
                                 {:limit sweep-cap}))))))

(def ^:private ^DateTimeFormatter hour-and-minute
  (DateTimeFormatter/ofPattern "HH:mm"))

(defn- clock-of
  "An instant as the row's zone shows it, in hours and minutes."
  [^Instant i zone]
  (.format hour-and-minute (.atZone i ^ZoneId (or (zone-id zone) ZoneOffset/UTC))))

(defn- grace-words [^long seconds]
  (cond
    (= 3600 seconds) "one-hour"
    (zero? (mod seconds 3600)) (str (quot seconds 3600) "-hour")
    (zero? (mod seconds 60)) (str (quot seconds 60) "-minute")
    :else (str seconds "-second")))

(defn- late
  "R-5.3: the sentence that skips a row found past its grace, or nil
  while it may still run late."
  [data ^Instant now]
  (let [^Instant at (:run_at data)
        grace (long (or (:grace_seconds data) 3600))]
    (when (and at (.isAfter now (.plusSeconds at grace)))
      (str "Not run: the engine was down at " (clock-of at (:zone data))
           " and came back at " (clock-of now (:zone data))
           ", past this action's " (grace-words grace) " grace."))))

(defn- quietly
  "One row's step, with its failure warned and survived, so the pass
  goes on to the next row. A row another hand moved first is no
  failure."
  [id step]
  (try
    (step)
    (catch clojure.lang.ExceptionInfo e
      (when-not (#{:wrong-state :not-found} (:waymark10/problem (ex-data e)))
        (warn! "the row " id " failed: " (ex-message e)))
      nil)
    (catch Exception e
      (warn! "the row " id " failed: " (ex-message e))
      nil)))

(defn sweep-due!
  "One pass of the clock, and the one call a test makes instead of
  waiting for it (R-5.1). Answers how many rows each step ended:
  `{:ran :late :recovered}`.

  RECOVERED: a row `running` for more than five minutes has no live
  runner, and is run again under its key (R-5.4). LATE: a `scheduled`
  row past its grace is skipped with a sentence (R-5.3). RAN: every
  other `scheduled` row whose minute has begun is claimed and run,
  oldest `run_at` first.

  `start!` IS THE CLAIM (R-5.2). The hook that runs this is elected,
  and two processes may still both believe they hold the role during a
  roll. Only the hand that moved the row to `running` runs its call."
  [eng]
  (if-not (contains? (inv/resources eng) kind)
    {:ran 0 :late 0 :recovered 0}
    (let [^Instant now ((:now-fn eng))
          stuck (rows-where eng :running :ran_at :<
                            (.minusSeconds now (long stuck-seconds)))
          recovered (count (filter (fn [row]
                                     (quietly (:id row) #(run! eng (:id row))))
                                   stuck))
          due (sort-by #(get-in % [:data :run_at])
                       (rows-where eng :scheduled :run_at :<= now))]
      (reduce (fn [tally row]
                (let [id (:id row)
                      why (late (:data row) now)
                      step (quietly id
                                    (fn []
                                      (cond
                                        why (do (end! eng id :skip why nil) :late)
                                        (start! eng id) (when (run! eng id) :ran))))]
                  (cond-> tally step (update step inc))))
              {:ran 0 :late 0 :recovered recovered}
              due))))

(defn start-sweeper!
  "The clock's loop, on `wakes/start-tick!`'s shape: a daemon thread, a
  latch, and a failure that is warned and survived. ONE process per
  database should run it, and that is not decided here: core's hook
  carries `:elected :scheduled-actions` (waymark10.modules). Returns
  the handle `stop-sweeper!` takes."
  [eng {:keys [interval-ms] :or {interval-ms default-sweep-ms}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep-due! eng)
                              (catch Exception e
                                (warn! "the sweep failed: "
                                       (ex-message e))))
                         (recur))))
                   "waymark10-scheduled-actions")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private time-fields
  "The time as the scheduler writes it: the create model's two entries,
  and the whole input of `reschedule`."
  [[:run_at {:examples ["2026-10-02T08:30:00-06:00"]
             :x-display {:raw true :label "Run at"
                         :help "When it runs: RFC 3339 with an offset (2026-10-02T08:30:00-06:00), or a local date and time (2026-10-02T08:30) read in the zone. At least one minute and at most 366 days ahead."}}
    [:string {:min 1 :max 40}]]
   [:zone {:optional true
           :x-display {:raw true :label "Zone"
                       :help "The IANA zone the time is written in and shown in, e.g. America/Denver. Left empty, a local time is read in the zone on your member row."}}
    [:maybe [:string {:min 1 :max 64}]]]])

(def ^:private target-field
  [:target {:x-display {:label "The call"
                        :help "The door this runs: a kind and an action, and the row's id unless the action creates one."}}
   [:map
    [:kind {:optional true :x-display {:raw true :label "Kind"}}
     [:maybe [:string {:min 1 :max 64}]]]
    [:action {:optional true :x-display {:raw true :label "Action"}}
     [:maybe [:string {:min 1 :max 64}]]]
    [:id {:optional true :x-ref {:kind-from :kind}
          :x-display {:raw true :label "Row"}}
     [:maybe [:string {:min 1 :max 128}]]]
    ;; child 4's, declared so the refusal names the child and not a key
    [:tool {:optional true :x-display {:raw true :label "Power tool"}}
     [:maybe [:string {:min 1 :max 120}]]]]])

(def ^:private rule-fields
  "What the scheduler chooses beside the call and its time."
  [[:input {:optional true
            :x-display {:label "Its input"
                        :help "The action's input, as the door itself takes it. Stored once, and sent as written when the time comes."
                        :spelled-by-hand "The target door's own input, wire-shaped; each door names its own keys, so no fixed form can offer them."}}
    [:maybe [:map-of :keyword :any]]]
   [:validity {:optional true
               :default "state"
               :x-display {:label "Runs only if"
                           :choices {"strict" "Nothing about the row changed since this was scheduled"
                                     "state" "The row is still in the state it was in"
                                     "conditions" "The row is still in that state and the conditions hold"}}}
    [:enum "strict" "state" "conditions"]]
   [:conditions {:optional true
                 :x-display {:label "Conditions"
                             :help "The scheduler's own conditions over the row's fields, in the collection filter's grammar. Not built yet: a row that names any is refused."
                             :spelled-by-hand "A map of filter parameter to value, the one a collection query takes; the target kind names its own filterable fields, so no fixed form can offer them."}}
    [:maybe [:map-of :keyword :string]]]
   [:expect_state {:optional true
                   :x-display {:raw true :label "Expected state"
                               :help "The state the row is expected to be in at the time, when it is not in it now."}}
    [:maybe [:string {:min 1 :max 64}]]]
   [:expect_refusals {:optional true
                      :x-display {:raw true :label "Expected refusals"
                                  :help "Guard names, as a refusal spells them, that would refuse the call now and are expected to lift by its time."}}
    [:maybe [:vector [:string {:min 1 :max 120}]]]]
   [:acknowledge {:optional true
                  :x-display {:label "Consequence acknowledged"
                              :help "The consequence sentence of a confirm door, exactly as the row states it now."}}
    [:maybe [:string {:min 1 :max 1000}]]]
   [:acknowledge_warnings {:optional true
                           :x-display {:raw true :label "Warnings accepted"
                                       :help "Guard names of warnings accepted in advance, as on an invoke."}}
    [:maybe [:vector [:string {:min 1 :max 120}]]]]
   [:grace_seconds {:optional true
                    :default 3600
                    :x-display {:label "Grace, in seconds"
                                :help "How late the run may be when the engine was down at its time. An hour unless said, a day at most."}}
    [:int {:min 0 :max 86400}]]
   [:tell {:optional true
           :default "all"
           :x-display {:label "Tell me"
                       :choices {"all" "When it runs, is skipped or fails"
                                 "problems" "Only when it is skipped or fails"
                                 "none" "Never"}}}
    [:enum "all" "problems" "none"]]])

(def ^:private outcome-shape
  "`{kind, action, id, state}` of the row a run wrote."
  [:maybe [:map
           [:kind {:x-display {:raw true :label "Kind"}} [:string {:min 1 :max 64}]]
           [:action {:x-display {:raw true :label "Action"}} [:string {:min 1 :max 64}]]
           [:id {:optional true :x-ref {:kind-from :kind}
                 :x-display {:raw true :label "Row"}}
            [:maybe [:string {:min 1 :max 128}]]]
           [:state {:optional true :x-display {:raw true :label "State"}}
            [:maybe [:string {:max 64}]]]]])

(def ^:private engine-fields
  "What the engine writes: at birth, at the run and at the ending."
  [[:scheduler {:x-ref {:principal true}
                :x-display {:raw true :label "Who scheduled it"
                            :help "The principal that scheduled this, stamped by the engine at birth. No input names it."}}
    [:string {:min 1 :max 128}]]
   [:acts_as {:x-display {:label "Runs as"
                          :help "The principal the run runs as, stamped by the engine from the scheduler. No input names it."}}
    [:map
     [:id {:x-ref {:principal true} :x-display {:raw true :label "Principal"}}
      [:string {:min 1 :max 128}]]
     [:type {:x-display {:raw true :label "Type"}} [:string {:min 1 :max 16}]]
     [:acts_for {:optional true :x-ref {:principal true}
                 :x-display {:raw true :label "Acts for"}}
      [:maybe [:string {:min 1 :max 256}]]]]]
   [:grant {:optional true :kind :grant
            :x-display {:label "Under the grant"
                        :help "The grant the scheduler wore, when it wore one. The run reads it again by this id."}}
    [:maybe :waymark/ref]]
   [:snapshot {:optional true
               :x-display {:label "As it was"
                           :help "The target's version, etag, state and law revision when this was scheduled."}}
    [:maybe [:map
             [:version {:optional true :x-display {:label "Version"}} [:maybe :int]]
             [:etag {:optional true :x-display {:raw true :label "Etag"}}
              [:maybe [:string {:max 256}]]]
             [:state {:optional true :x-display {:raw true :label "State"}}
              [:maybe [:string {:max 64}]]]
             [:law_revision {:optional true :x-display {:raw true :label "Law revision"}}
              [:maybe [:string {:max 128}]]]]]]
   [:held_call {:optional true :kind :held_call
                :x-display {:label "Approved by"
                            :help "The held call that carries a person's yes, when the call needed one."}}
    [:maybe :waymark/ref]]
   [:ran_at {:optional true
             :x-display {:label "Ran at" :help "When the run began."}}
    [:maybe :waymark/instant]]
   [:outcome {:optional true
              :x-display {:label "What it wrote"
                          :help "The row the call wrote, and the state it left it in."}}
    outcome-shape]
   [:outcome_why {:optional true
                  :x-display {:label "How it ended"
                              :help "One sentence, written at the run and kept: what was to happen, when, and what did."}}
    [:maybe [:string {:max 240}]]]])

(def ^:private why-input
  [:map
   [:outcome_why {:optional true :x-display {:hidden true}}
    [:maybe [:string {:max 240}]]]])

(def ^:private ending-input
  (conj why-input [:outcome {:optional true :x-display {:hidden true}} outcome-shape]))

(defn- engine-door
  "One of the engine's own doors. An ending takes `input` and keeps it."
  [from to label one-way & [input]]
  (cond-> {:from from :to to
           :guards [the-engine-runs-it]
           :safety {:idempotent true :reversible false :confirm false
                    :one-way one-way}
           :display {:label label}}
    input (assoc :input input
                 :handler record-ending
                 :edit {:fence false
                        :unfenced-reason "The engine's own record of how the call ended. No read preceded it to fence against."})))

(defresource scheduled-action
  {:kind :scheduled_action
   :plural "scheduled_actions"
   :nav :system
   :states [:scheduled :running :done :skipped :failed :cancelled]
   :initial :scheduled
   :terminal #{:done :skipped :failed :cancelled}
   :summary "{data.target.action} {data.target.kind} · {data.run_at} · {state}"
   :label-template "{data.target.action} {data.target.kind}"
   ;; WHOEVER SCHEDULED IT READS IT AND STOPS IT WITH NO FURTHER GRANT
   ;; (R-1). The guards still judge both doors; this only decides that
   ;; the scheduler can see its own row well enough to knock.
   :own-surface {:by :scheduler :actions #{:cancel :reschedule}}
   :schema
   (-> [:map
        target-field
        [:run_at {:x-display {:label "Runs at"
                              :help "The instant it runs, in UTC and whole minutes."}}
         :waymark/instant]
        [:zone {:x-display {:raw true :label "Zone"
                            :help "The zone the time was written in and is shown in."}}
         [:string {:min 1 :max 64}]]]
       (into rule-fields)
       (into engine-fields))
   ;; the stamps and the endings are the engine's to write
   :create-schema (-> [:map target-field]
                      (into time-fields)
                      (into rule-fields))
   ;; `scheduler` is filterable because the own surface and the limit
   ;; both ask for one scheduler's rows.
   :filterable {:state #{:eq :in}
                :scheduler #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :default-filters {:state "scheduled"}
   :create-guards [the-target-is-an-engine-door the-rule-is-built the-input-fits
                   the-time-names-one-instant the-time-is-in-range
                   the-scheduler-has-room the-door-would-take-it]
   :on-create born
   :actions
   {:reschedule
    {:from #{:scheduled} :to :scheduled
     :input (into [:map] time-fields)
     :guards [the-scheduler-moves-it the-time-names-one-instant the-time-is-in-range]
     :handler move-time
     :edit {:fence false
            :unfenced-reason "Only the time moves, and only its scheduler moves it; the call and its rule stay as they were written, so there is no second writer's work here to clobber."}
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Reschedule" :order 1
               :description "Move this to another time; the call and the rule it runs under stay as they are"}}
    :cancel
    {:from #{:scheduled} :to :cancelled
     :guards [the-scheduler-moves-it]
     :safety {:idempotent true :reversible false :confirm false
              :final "The call never runs. Its time may already have passed, so reopening would make the record lie; scheduling it again is a new row."}
     :display {:label "Cancel" :style :danger :order 2
               :description "Stop this before its time; the call never runs"}}
    ;; the engine's own hand: the claim, and the three endings
    :start (assoc (engine-door #{:scheduled} :running "Started"
                               "The claim: one runner holds this action, and its call is under way.")
                  :handler record-start)
    :land (engine-door #{:running} :done "Ran"
                       "The call ran and what it wrote is on the row. One row is one run."
                       ending-input)
    :skip (engine-door #{:scheduled :running} :skipped "Skipped"
                       "The validity rule did its work: the call did not run, and scheduling it again is a new row."
                       why-input)
    :fail (engine-door #{:running} :failed "Failed"
                       "The call was attempted and did not land. Scheduling it again is a new row."
                       why-input)}
   :deviations
   ["R-1 draws `proposed` and `arm`. They are child 4's (approval at scheduling) and are not declared here, so a row is always born `scheduled` and `cancel` leaves from `scheduled` alone."
    "R-1 says `input` is capped as held_calls/capped caps. A cut input is a different call, so an input over the same 16 KB ceiling is refused at scheduling with a sentence and never stored cut."
    "R-1 lists `etag` in the snapshot. An etag is spelled from the kind, the id and the version, so the snapshot keeps `version` and a reader spells the etag from it."
    "R-1 says `reschedule` runs the scheduling check again. It does not yet: the check runs at the create, and a moved row meets its door at the run."
    "R-3.1 asks a confirm door for its sentence. The sentence is asked of a row's door; a create target is rehearsed in full and asked for none."
    "R-6.2 says the summary is `outcome_why` after an ending. A summary is one template, so the line stays `{target} · {run_at} · {state}` and the sentence is read from the field."
    "R-7.2 states the zone rules for `at`. The same rules are applied here to `run_at` on the create and on `reschedule`, and an instant written with an offset and no zone anywhere is shown in that offset."
    "R-2 reads the target under the runner's grant as it is then. The run reads the grant by its id and judges the door, the row and the call's guards under it. The validity rule's own read of the row's state and version is the engine's, so a row the grant no longer shows is skipped in the grant's sentence and not in the sentence of a row that is gone."
    "R-4.2 names three ways a grant stops a run: revoked, expired, narrowed. They are one sentence here, and a seat that is not open has its own. A seat held for named models is judged with the model the scheduling session declared, read off this row's first transition, because a run has no session to declare one."
    "R-4.2 says the run counts on no sitting. A sitting's transitions are the log rows whose actor names its grant, so the run's actor names no grant: it carries `scheduled`, and the grant's id stays on this row."
    "R-5.1 takes due rows oldest first. `run_at` has no promoted column to order by, so a pass takes up to its cap of the due rows in the order they were scheduled and runs that page oldest `run_at` first."
    "R-5.3's sentence says the engine was down. The sweep cannot tell a stopped engine from a slow pass, and says it either way."
    "R-5.4 fails a power tool that was left `running`. Targets are engine doors until child 4, so every row left `running` is run again under its key."
    "R-2.1 sends the snapshot's etag as If-Match. The fence judges it only on a fenced door, so the run also compares the row's version with the snapshot's before the call; on an unfenced door a write between that read and the call is not caught."
    "R-2.2 asks that the row's envelope still advertises the action. The run reads the door's declared from-states and leaves the rest to the dry run, which judges the guards the envelope would."]})
