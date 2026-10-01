(ns waymark10.server.scheduled
  "The scheduled action (docs/spec-scheduled-actions.md, child 1a): a
  call stored for a time, as a kind of its own.

  THIS NAMESPACE IS THE KIND AND NOTHING ELSE. The create validates
  and stores. The scheduling check is child 1b's and the run is child
  1c's: `start`, `land`, `skip` and `fail` are declared so the machine
  is whole, and only the engine's own hand walks them.

  NOBODY SCHEDULES AS SOMEBODY ELSE (R-4.2). `scheduler`, `acts_as`
  and `grant` are stamped at birth, and the closed create model
  refuses a body that names one.

  THE ENGINE NEVER GUESSES UTC (R-7.2). `run_at` is RFC 3339 with an
  offset, or a local time read in the `zone` the body names, else in
  the zone on the scheduler's member row or its person's. From then
  on it is an instant in whole minutes."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t])
  (:import (java.nio.charset StandardCharsets)
           (java.time DateTimeException Instant LocalDateTime OffsetDateTime
                      ZoneId ZonedDateTime)
           (java.time.temporal ChronoUnit)))

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

;; ── handlers ────────────────────────────────────────────────────────

(defn- born
  "The birth stamps, read from the principal and never from the body:
  who scheduled it, who the run runs as, and the id of the grant it
  wore. `run_at` becomes the instant the body named, in whole minutes,
  and `zone` the zone it is shown in."
  [row ctx]
  (let [p (:principal ctx)
        {:keys [instant zone]} (time-of nil (:data row) ctx)
        acts-for (some-> (:acts-for p) str not-empty)
        grant (some-> (get-in ctx [:grant :id]) str not-empty)]
    (update row :data
            #(cond-> (assoc %
                            :scheduler (str (:id p))
                            :acts_as (cond-> {:id (str (:id p))
                                              :type (name (or (:type p) :human))}
                                       acts-for (assoc :acts_for acts-for))
                            :run_at instant
                            :zone zone)
               grant (assoc :grant grant)))))

(defhandler move-time [row inp ctx]
  (let [{:keys [instant zone]} (time-of row inp ctx)]
    (update row :data assoc :run_at instant :zone zone)))

(defhandler record-start [row _inp ctx]
  (assoc-in row [:data :ran_at] (:now ctx)))

(defhandler record-ending [row inp _ctx]
  (update row :data merge
          (into {} (filter (comp some? val))
                (select-keys inp [:outcome :outcome_why]))))

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
                   the-scheduler-has-room]
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
    "R-1 lists `snapshot`, `expect_state`, `expect_refusals`, `acknowledge` and `acknowledge_warnings`. The fields are declared and stored; the scheduling check that reads them and writes the snapshot is child 1b's."
    "R-6.2 says the summary is `outcome_why` after an ending. A summary is one template, so the line stays `{target} · {run_at} · {state}` and the sentence is read from the field."
    "R-7.2 states the zone rules for `at`. The same rules are applied here to `run_at` on the create and on `reschedule`, and an instant written with an offset and no zone anywhere is shown in that offset."]})
