(ns waymark10.retention
  "Retention as declared law (docs/spec-documents.md §4, Documents 5):

    :keep {:for '(years 7) :from :last_visit_on :then :purge}
    :keep {:for '(years 7) :from {:state :fulfilled} :then :purge}
    :keep :forever

  `:for` is a concept 2 period, (days n) (weeks n) (months n) or
  (years n). `:from` is a date field, or a state whose entry the log
  dates — that instant's day in the house's zone. The due date is the
  one plus the other. A null `:from` is never due: the row is kept
  until it is set. `:then` names the door that ends the keeping, and
  the declaration check refuses any but a :purge true door.
  `:keep :forever` says the kind is kept on purpose.

  From it the engine writes three things: `retention-has-passed` on
  the :then door (`weave`), so nobody purges early; the due date a
  row's envelope shows as meta.retained_until (`due-on`); and the
  elected sweep, waymark10.server.retention, that invokes :then on each
  due row as the engine's retention actor.

  A house no work_calendar governs reads its day in UTC, here and in
  the sweep alike."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.summary :as summary]
            [waymark10.types :as t])
  (:import (java.time Instant LocalDate Period ZoneId ZoneOffset)
           (java.time.format DateTimeFormatter DateTimeParseException)
           (java.util Date Locale)))

(set! *warn-on-reflection* true)

;; ── the declaration ─────────────────────────────────────────────────

(defn period
  "A :for form → its java.time.Period, or nil when it is not one."
  [form]
  (cond
    (instance? Period form) form
    (and (seq? form) (= 2 (count form)) (symbol? (first form))
         (pos-int? (second form)))
    (let [n (int (second form))]
      (case (symbol (name (first form)))
        days (Period/ofDays n)
        weeks (Period/ofWeeks n)
        months (Period/ofMonths n)
        years (Period/ofYears n)
        nil))
    :else nil))

(defn check!
  "Judge a kind's :keep at its def site: :forever, or {:for :from
  :then} with a period, a date field or {:state s} of a declared state,
  and a door's name. Whether that field is a filterable date and that
  door a purge is the declaration check's ([keep-unfiltered],
  [purge-unheld])."
  [rmap]
  (let [k (:keep rmap)
        bad (fn [msg]
              (throw (t/definition-error
                      (str (some-> (:kind rmap) name) " :keep — " msg))))]
    (when (and (some? k) (not= :forever k))
      (when-not (map? k)
        (bad "is :forever, or {:for :from :then}"))
      (when-some [extra (seq (remove #{:for :from :then} (keys k)))]
        (bad (str "takes :for, :from and :then, not " (vec extra))))
      (when-not (period (:for k))
        (bad (str ":for is a period, '(days n) '(weeks n) '(months n) or "
                  "'(years n), not " (pr-str (:for k)))))
      (let [from (:from k)]
        (cond
          (keyword? from) nil
          (and (map? from) (= [:state] (keys from)))
          (when-not (some #{(:state from)} (:states rmap))
            (bad (str ":from {:state " (pr-str (:state from))
                      "} names no state of the kind")))
          :else (bad ":from is a date field, or {:state s}")))
      (when-not (keyword? (:then k))
        (bad ":then names the purge door that ends the keeping"))))
  nil)

;; ── the due date ────────────────────────────────────────────────────

(defn- as-date [v]
  (cond (instance? LocalDate v) v
        (string? v) (try (LocalDate/parse ^String v)
                         (catch DateTimeParseException _ nil))))

(defn- as-instant [v]
  (cond (instance? Instant v) v
        (instance? Date v) (.toInstant ^Date v)
        (string? v) (try (Instant/parse ^String v)
                         (catch DateTimeParseException _ nil))))

(defn entered
  "The instant the row last entered `state`, read from its log newest
  first, or nil when it never did."
  [transitions state]
  (some (fn [tr]
          (when (= (name state) (some-> (:to-state tr) name))
            (as-instant (:at tr))))
        transitions))

(defn zone-of
  "The zone of the work_calendar a `:calendar` hook names as governing,
  or UTC when there is no hook or nothing governs."
  ^ZoneId [calendar-of]
  (or (when calendar-of
        (try (:zone (calendar-of)) (catch Exception _ nil)))
      ZoneOffset/UTC))

(defn due-on
  "The date `keep` lets `row` go, or nil while it is not due to: no
  :keep map, a null :from field, a state the row never entered.
  `entered-at` is (fn [row state]) → the instant it last entered it."
  [keep row entered-at ^ZoneId zone]
  (when-some [p (when (map? keep) (period (:for keep)))]
    (let [from (:from keep)
          start (if (map? from)
                  (when-some [at (when entered-at
                                   (as-instant (entered-at row (:state from))))]
                    (.toLocalDate (.atZone ^Instant at zone)))
                  (as-date (get-in row [:data from])))]
      (when start
        (.plus ^LocalDate start ^Period p)))))

;; ── the words ───────────────────────────────────────────────────────

(def ^:private spoken-date
  (DateTimeFormatter/ofPattern "d MMMM yyyy" Locale/ENGLISH))

(defn spoken
  "A date as a sentence says it: 14 March 2031."
  [^LocalDate d]
  (.format d spoken-date))

(defn- period-words
  "(years 7) → \"7-year\"."
  [form]
  (if (seq? form)
    (str (second form) "-" (str/replace (name (first form)) #"s$" ""))
    (str form)))

(defn- period-phrase
  "(years 7) → \"7 years\", (years 1) → \"1 year\"."
  [form]
  (if (seq? form)
    (let [n (second form)
          unit (name (first form))]
      (str n " " (if (= 1 n) (str/replace unit #"s$" "") unit)))
    (str form)))

(defn- words [k]
  (str/replace (name k) "_" " "))

(defn swept-why
  "The sweep's sentence on the purge it makes: purged by the 7-year
  retention of patient, due 14 March 2031."
  [rdef ^LocalDate due]
  (str "purged by the " (period-words (get-in rdef [:keep :for]))
       " retention of " (words (:kind rdef)) ", due " (spoken due)))

;; ── the guard ───────────────────────────────────────────────────────

(defn- today ^LocalDate [ctx ^ZoneId zone]
  (when-some [now (as-instant (:now ctx))]
    (.toLocalDate (.atZone ^Instant now zone))))

(defn guard
  "retention-has-passed: the :then door opens on the due date and not
  before. A row not yet due — a null :from among them — is refused in
  one sentence: Ana Ruiz's records are kept until 14 March 2031."
  [rmap]
  (let [keep (:keep rmap)
        from (:from keep)
        line (select-keys rmap [:summary :summary-fn])]
    (g/guard
     {:name :retention-has-passed
      :reads [:now :calendar :transitions]
      :vars [:who :until]
      :explain "{who}'s records are kept {until}."
      :check (with-meta
               (fn [row _inp ctx]
                 (let [zone (zone-of (:calendar ctx))
                       due (due-on keep row (:entered-at ctx) zone)
                       on (today ctx zone)]
                   (if (and due on (not (.isBefore on ^LocalDate due)))
                     (t/allow)
                     (t/deny {:vars {:who (let [s (summary/line line row)]
                                            (if (str/blank? s) "This row" s))
                                     :until (cond
                                              due (str "until " (spoken due))
                                              (map? from)
                                              (str "for " (period-phrase (:for keep))
                                                   " once it is " (words (:state from)))
                                              :else
                                              (str "until " (words from) " is set"))}}))))
               {:waymark10/form (list 'retention-has-passed
                                      (:for keep) from (:then keep))})})))

(defn weave
  "The rmap with retention-has-passed on the door its :keep names, when
  it declares a :keep map and that door; any other rmap as it came."
  [rmap]
  (let [k (:keep rmap)]
    (if (and (map? k) (get-in rmap [:actions (:then k)]))
      (update-in rmap [:actions (:then k) :guards]
                 #(conj (vec %) (guard rmap)))
      rmap)))
