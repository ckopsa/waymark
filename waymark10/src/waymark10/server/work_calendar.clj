(ns waymark10.server.work-calendar
  "The work calendar (docs/spec-calendar-time.md §3): which days a house
  works, the hours it keeps and its holidays, as a ROW and not a
  declaration. A holiday is decided in December by the person who runs
  the house, not by a deploy, so adding one moves verdicts without a
  law revision.

  The law reads it through the `:calendar` hook on the ctx: `(hook read
  find)` over a ctx's own :read and :find, a function of the row's
  calendar ref or of nothing (then the default row). It answers the
  governing calendar — its id and version, which a verdict keeps as
  evidence, and its zone, workdays, hours and holidays as values — or
  nil when nothing governs: no default row, or a ref that names no
  active row. `governing` is the reader's door: a ctx with no hook, or a
  hook that answers nil, is `expr/unknown`, never an exception."
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.types :as t])
  (:import (java.time DateTimeException LocalDate LocalTime ZoneId)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(def kind :work_calendar)

(def weekdays
  "The weekday tokens, Monday first."
  ["mon" "tue" "wed" "thu" "fri" "sat" "sun"])

(def default-workdays
  "The workdays of a calendar that names none."
  #{:mon :tue :wed :thu :fri})

;; ── values ──────────────────────────────────────────────────────────

(defn- zone-of
  "The ZoneId `z` names, or nil."
  [z]
  (when (and (string? z) (not (str/blank? z)))
    (try (ZoneId/of ^String z)
         (catch DateTimeException _ nil))))

(defn- clock-of
  "The LocalTime of an HH:MM string, or nil."
  [s]
  (when (and (string? s) (re-matches #"\d{2}:\d{2}" s))
    (try (LocalTime/parse ^String s)
         (catch DateTimeParseException _ nil))))

(defn- date-of
  "A holiday's date, decoded or still on the wire, or nil."
  [v]
  (cond (instance? LocalDate v) v
        (string? v) (try (LocalDate/parse ^String v)
                         (catch DateTimeParseException _ nil))))

(defn- in-week-order
  "The workday tokens of `ds`, once each, Monday first."
  [ds]
  (filterv (set (map name ds)) weekdays))

;; ── the guards ──────────────────────────────────────────────────────

(defn- a-person?
  "Whether the caller of `ctx` is a person, in person or through a tool
  the person is signed in to."
  [ctx]
  (let [{:keys [type acts-for]} (:principal ctx)]
    (or (= :human type)
        (and (= :agent type) (not (str/blank? (str acts-for)))))))

(g/defguard a-person-keeps-the-calendar
  {:reads [:principal]
   :open "No door changes who the caller is: ask a person to change the calendar."
   :explain "Which days the house works and which are holidays is a person's decision, so only a person changes the calendar — in person, or through a tool the person is signed in to."}
  [_row _inp ctx]
  (if (a-person? ctx) (t/allow) (t/deny)))

(g/defguard zone-is-a-zone
  {:reads []
   :vars [:zone]
   :open "Name an IANA time zone, as America/Denver is one."
   :explain "{zone} is not an IANA time zone. Name one, as America/Denver is one."}
  [row inp _ctx]
  (let [z (if (contains? inp :zone) (:zone inp) (get-in row [:data :zone]))]
    (if (or (nil? z) (zone-of z))
      (t/allow)
      (t/deny {:vars {:zone z}}))))

(g/defguard hours-are-one-day
  {:reads []
   :open "Give the hours as two clock times on one day, the first the earlier: 07:00 to 17:00."
   :explain "The hours are two clock times, HH:MM, and the day starts before it ends."}
  [row inp _ctx]
  (let [h (if (contains? inp :hours) (:hours inp) (get-in row [:data :hours]))
        from (clock-of (:from h))
        to (clock-of (:to h))]
    (if (or (nil? h) (and from to (.isBefore ^LocalTime from to)))
      (t/allow)
      (t/deny))))

(defn- active? [row]
  (= "active" (some-> (:state row) name)))

(defn- default? [row]
  (true? (get-in row [:data :default])))

(defn- other-default
  "The name of an active default calendar other than `row`, or nil —
  nil too on a ctx with no :find, which declines to guess."
  [row ctx]
  (when-some [find' (:find ctx)]
    (some (fn [r]
            (when (and (active? r) (default? r)
                       (not= (str (:id r)) (str (:id row))))
              (or (get-in r [:data :name]) (str (:id r)))))
          (find' kind {:state "active"} {:limit 100}))))

(g/defguard one-default-at-birth
  {:reads [:work_calendar]
   :vars [:other]
   :open "Leave this calendar's default off, or clear the default on {other} first."
   :explain "{other} is already the default calendar, and an engine has one."}
  [row inp ctx]
  (let [other (when (true? (:default inp)) (other-default row ctx))]
    (if other
      (t/deny {:vars {:other other}})
      (t/allow))))

(g/defguard one-default
  {:reads [:work_calendar]
   :vars [:other]
   :open "Clear the default on {other}, then make this calendar the default."
   :explain "{other} is already the default calendar, and an engine has one."}
  [row _inp ctx]
  (if-some [other (other-default row ctx)]
    (t/deny {:vars {:other other}})
    (t/allow)))

;; ── the handlers ────────────────────────────────────────────────────

(defhandler restate-calendar
  [row inp _ctx]
  ;; a patch: a field the input leaves out keeps its stored value
  (update row :data merge
          (cond-> (select-keys inp [:name :zone :hours])
            (contains? inp :workdays)
            (assoc :workdays (some-> (:workdays inp) in-week-order not-empty)))))

(defn- without-day [holidays on]
  (remove #(= on (date-of (:on %))) holidays))

(defhandler add-holiday
  [row inp _ctx]
  ;; one holiday a day: naming a day again renames it
  (let [on (date-of (:on inp))]
    (update-in row [:data :holidays]
               (fn [hs]
                 (->> (cons {:on on :name (:holiday inp)} (without-day hs on))
                      (sort-by (comp date-of :on))
                      vec)))))

(defhandler remove-holiday
  [row inp _ctx]
  (update-in row [:data :holidays]
             #(vec (without-day % (date-of (:on inp))))))

(defhandler make-default [row _inp _ctx]
  (assoc-in row [:data :default] true))

(defhandler clear-default [row _inp _ctx]
  (assoc-in row [:data :default] false))

;; ── the fields ──────────────────────────────────────────────────────

(def ^:private name-field
  [:name {:examples ["Elm Street Landscaping"]
          :x-display
          {:label "Name"
           :help "What the house calls this calendar: the house's own name, or a crew's."}}
   [:string {:min 1 :max 120}]])

(def ^:private zone-field
  [:zone {:examples ["America/Denver"]
          :x-display
          {:label "Time zone"
           :help "The IANA zone the house's days begin and end in, as America/Denver is one."}}
   [:string {:min 1 :max 64}]])

(def ^:private workdays-field
  [:workdays {:optional true
              :examples [["mon" "tue" "wed" "thu" "fri"]]
              :x-display
              {:label "Workdays"
               :help "The days of the week the house works. Left blank it works Monday to Friday."}}
   [:maybe [:vector {:min 1 :max 7} (into [:enum] weekdays)]]])

(def ^:private hours-field
  [:hours {:optional true
           :examples [{:from "07:00" :to "17:00"}]
           :x-display
           {:label "Hours"
            :help "The hours of a working day, local to the zone. Left blank a working day is the whole day."}}
   [:maybe
    [:map
     [:from {:x-display {:label "From" :help "When the working day starts, HH:MM."}}
      [:string {:min 5 :max 5}]]
     [:to {:x-display {:label "To" :help "When the working day ends, HH:MM."}}
      [:string {:min 5 :max 5}]]]]])

(def ^:private stated-fields
  [name-field zone-field workdays-field hours-field])

(def ^:private holidays-field
  [:holidays {:optional true
              :x-display
              {:label "Holidays"
               :help "The days the house does not work though they are workdays. The add and remove holiday doors keep this list."}}
   [:maybe
    [:vector {:max 400}
     [:map
      [:on {:x-display {:label "On" :help "The day of the holiday."}}
       :waymark/date]
      [:name {:x-display {:label "Holiday" :help "What the house calls the day."}}
       [:string {:min 1 :max 120}]]]]]])

(def ^:private default-field
  [:default {:optional true
             :x-display
             {:label "The default calendar"
              :help "On for the one calendar that governs when nothing names another. An engine has one."}}
   [:maybe :boolean]])

(defresource work-calendar
  {:kind :work_calendar
   :plural "work_calendars"
   :nav :secondary
   :states [:active :retired]
   :initial :active
   :terminal #{}
   :summary "{data.name} · {data.zone} · {state}"
   :label-template "{data.name}"
   :unique [[:name]]
   :schema (-> (into [:map] stated-fields)
               (conj holidays-field default-field))
   :filterable {:state #{:eq :in}
                :name #{:eq}}
   :sortable {:fields [:name] :default "name"}
   :create-guards [a-person-keeps-the-calendar
                   zone-is-a-zone
                   hours-are-one-day
                   one-default-at-birth]
   :actions
   {:restate
    {:from #{:active} :to :active
     :input (into [:map] (map #(update % 1 assoc :optional true)) stated-fields)
     :edit {:prefill [:name :zone :workdays :hours]}
     :guards [a-person-keeps-the-calendar zone-is-a-zone hours-are-one-day]
     :handler restate-calendar
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The calendar holds what this restate says; the values before are not kept."}
     :display {:label "Restate" :order 2
               :description "Change the calendar's name, zone, workdays or hours"}}

    :add_holiday
    {:from #{:active} :to :active
     :input [:map
             [:on {:x-display {:label "On" :help "The day the house does not work."}}
              :waymark/date]
             [:holiday {:examples ["Memorial Day"]
                        :x-display {:label "Holiday" :help "What the house calls the day."}}
              [:string {:min 1 :max 120}]]]
     :guards [a-person-keeps-the-calendar]
     :handler add-holiday
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Add a holiday" :order 1
               :description "Name a day the house does not work; naming a day again renames it"}}

    :remove_holiday
    {:from #{:active} :to :active
     :input [:map
             [:on {:x-display {:label "On" :help "The holiday's day."}}
              :waymark/date]]
     :guards [a-person-keeps-the-calendar]
     :handler remove-holiday
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Remove a holiday" :order 3
               :description "Work on a day the calendar kept as a holiday"}}

    :make_default
    {:from #{:active} :to :active
     :guards [a-person-keeps-the-calendar one-default]
     :handler make-default
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Make it the default" :order 4
               :description "Govern every row that names no calendar of its own"}}

    :clear_default
    {:from #{:active} :to :active
     :guards [a-person-keeps-the-calendar]
     :handler clear-default
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Clear the default" :order 5
               :description "Stop governing the rows that name no calendar; until another is the default, their business days are unknown"}}

    ;; a retired calendar governs nothing, so it is the default no more
    :retire
    {:from #{:active} :to :retired
     :guards [a-person-keeps-the-calendar]
     :handler clear-default
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Retire" :style :danger :order 9
               :description "Stop governing every row; a row that names it reads its business days as unknown"}}

    :restore
    {:from #{:retired} :to :active
     :guards [a-person-keeps-the-calendar]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Restore" :order 1
               :description "Govern the rows that name this calendar again"}}}})

;; ── the hook ────────────────────────────────────────────────────────

(defn answer
  "What the hook answers for one decoded row: the id and version a
  verdict keeps as evidence, the zone a ZoneId, the workdays a set of
  keywords, the hours {:from :to} LocalTimes or nil for the whole day,
  and the holidays a sorted map of LocalDate to name. nil for a row
  whose zone names none."
  [row]
  (let [d (:data row)]
    (when-some [zone (zone-of (:zone d))]
      {:id (str (:id row))
       :version (:version row)
       :name (:name d)
       :zone zone
       :workdays (if (seq (:workdays d))
                   (into #{} (map keyword) (:workdays d))
                   default-workdays)
       :hours (let [from (clock-of (get-in d [:hours :from]))
                    to (clock-of (get-in d [:hours :to]))]
                (when (and from to) {:from from :to to}))
       :holidays (into (sorted-map)
                       (keep (fn [h] (when-some [on (date-of (:on h))]
                                       [on (:name h)])))
                       (:holidays d))})))

(def ^:private row-id-form
  #"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

(defn hook
  "The `:calendar` hook over a ctx's `read` and `find`: called with no
  ref, or a nil one, it answers the active default row; with a ref, the
  active row it names. nil when nothing governs — and nil, not a hook,
  when either read is missing."
  [read' find']
  (when (and read' find')
    (fn calendar-of
      ([] (some->> (find' kind {:state "active"} {:limit 100})
                   (filter #(and (active? %) (default? %)))
                   first
                   answer))
      ([ref]
       (cond
         (nil? ref) (calendar-of)
         (not (re-matches row-id-form (str ref))) nil
         :else (let [row (read' kind (str ref))]
                 (when (and row (active? row))
                   (answer row))))))))

(defn governing
  "The calendar `ref` names under `ctx` (no ref: the default), as the
  ctx's :calendar hook answers it — or `expr/unknown`, when the ctx has
  no hook or the hook finds nothing that governs."
  ([ctx] (governing ctx nil))
  ([ctx ref]
   (if-some [calendar-of (:calendar ctx)]
     (if-some [c (calendar-of ref)] c expr/unknown)
     expr/unknown)))
