(ns mealplan10.event-source
  "The event boundary, declared as a Mirror's adapter (see
  mealplan10.resources.event).

  The real adapter pulls straight from the private Google Calendar
  iCal feed (Settings → Integrate calendar → \"Secret address in iCal
  format\"). That URL is a bearer secret: anyone holding it can read
  the calendar, so it lives only in MEALPLAN_GCAL_ICS_URL, never in
  source.

  A feed has no natural resource ids, so an occurrence's external_id
  is {uid}@{start date} — the pairing that makes one VEVENT's
  occurrences distinct mirrored resources (each with its own date,
  exactly what the plan's overlap predicate needs).

  Recorded scope (a deviation from mealplan9, named): the iCal parse
  here is line-unfolding + VEVENT field extraction in plain Clojure —
  no RRULE expansion, so a recurring series contributes only its
  DTSTART occurrence. mealplan9 leaned on recurring_ical_events; a
  pure-Clojure recurrence expander is unearned until the family's
  recurring events matter to a plan. Bad DTSTART years (observed:
  year 1 in real feeds) are dropped per-event, never the whole feed.

  FakeEvents is the scriptable in-memory twin — the tests' instrument
  and the offline-dev default (as FakeFunds is to WarehouseFunds)."
  (:require [clojure.string :as str]
            [waymark10.server.mirror :as mirror]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration LocalDate)))

(set! *warn-on-reflection* true)

(defn- content-etag [{:keys [title date kind]}]
  (wire/sha256-hex (str title "|" date "|" (or kind "note"))))

(defn- doc+etag [doc]
  [doc (content-etag doc)])

;; ── the real boundary: the Google Calendar iCal feed ────────────────

(def ^:private window-past-days 7)
(def ^:private window-future-days 90)

;; a sane bound on a VEVENT's DTSTART year: real calendars only need
;; this century either side (observed corruption: year 1)
(def ^:private min-sane-year 1900)
(def ^:private max-sane-year 2200)

(defn- unfold-lines
  "RFC 5545 line unfolding: a line starting with space/tab continues
  the previous one."
  [^String body]
  (-> body
      (str/replace #"\r\n[ \t]" "")
      (str/replace #"\n[ \t]" "")
      (str/split-lines)))

(defn- vevents
  "The BEGIN:VEVENT…END:VEVENT blocks, each as a {PROP value} map
  (property parameters stripped: DTSTART;VALUE=DATE → DTSTART)."
  [lines]
  (loop [lines lines, current nil, out []]
    (if-some [^String line (first lines)]
      (cond
        (= "BEGIN:VEVENT" line) (recur (rest lines) {} out)
        (= "END:VEVENT" line) (recur (rest lines) nil
                                     (if current (conj out current) out))
        (some? current)
        (let [i (.indexOf line ":")]
          (if (neg? i)
            (recur (rest lines) current out)
            (let [k (first (str/split (subs line 0 i) #";"))
                  v (subs line (inc i))]
              (recur (rest lines) (assoc current k v) out))))
        :else (recur (rest lines) current out))
      out)))

(defn- dtstart-date
  "A DTSTART value's date part: 20260714 or 20260714T183000Z →
  LocalDate; nil when unparseable or outside the sane-year bound."
  [^String v]
  (when (and v (<= 8 (count v)))
    (let [ymd (subs v 0 8)]
      (try
        (let [d (LocalDate/parse ymd (java.time.format.DateTimeFormatter/BASIC_ISO_DATE))]
          (when (<= min-sane-year (.getYear d) max-sane-year)
            d))
        (catch Exception _ nil)))))

(defn- fetch-ics ^String [^String url]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 10))
                (.GET)
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode resp) 299)
      (throw (ex-info (str "calendar feed answered " (.statusCode resp)) {})))
    (.body resp)))

(defn- occurrence-docs
  "One feed fetch → {external-id doc} for every VEVENT whose date
  falls in the rolling window. The feed is small (a personal
  calendar), so fetching whole and filtering in memory beats any
  partial-fetch scheme. Every occurrence is minted kind \"note\" —
  the feed carries no signal for \"this evening is spoken for\"."
  [ics-url]
  (let [today (LocalDate/now)
        lo (.minusDays today (long window-past-days))
        hi (.plusDays today (long window-future-days))]
    (into {}
          (keep (fn [ev]
                  (when-some [^LocalDate d (dtstart-date (get ev "DTSTART"))]
                    (when (and (not (.isBefore d lo)) (not (.isAfter d hi)))
                      [(str (get ev "UID" "no-uid") "@" d)
                       {:title (get ev "SUMMARY" "Untitled")
                        :date (str d)
                        :kind "note"}]))))
          (vevents (unfold-lines (fetch-ics ics-url))))))

(defrecord GoogleCalendarEvents [ics-url]
  mirror/MirrorAdapter
  (discover [_]
    (vec (keys (occurrence-docs ics-url))))
  (pull [_ xid]
    (or (some-> (get (occurrence-docs ics-url) xid) doc+etag)
        (throw (ex-info (str "no calendar occurrence for " xid
                             " in the current window") {}))))
  (pull-many [_ xids]
    (let [docs (occurrence-docs ics-url)]
      (into {}
            (keep (fn [x] (when-some [d (get docs x)] [x (doc+etag d)])))
            xids))))

(defn google-calendar-events [ics-url]
  (->GoogleCalendarEvents ics-url))

;; ── the scriptable twin ─────────────────────────────────────────────

(defrecord FakeEvents [state]
  mirror/MirrorAdapter
  (discover [_]
    (let [{:keys [down discoverable]} @state]
      (when down (throw (ex-info "calendar feed unreachable" {})))
      (vec discoverable)))
  (pull [_ xid]
    (swap! state update :pulls inc)
    (let [{:keys [down removed docs]} @state]
      (when down (throw (ex-info "calendar feed unreachable" {})))
      (when (contains? removed xid)
        (throw (ex-info (str xid " is no longer on the calendar") {})))
      (doc+etag (or (get docs xid)
                    ;; an unseeded id auto-vivifies — unlike a REMOVED
                    ;; one, which fails like a real feed no longer
                    ;; carrying it
                    {:title xid :date (str (LocalDate/now)) :kind "note"}))))
  (pull-many [_ xids]
    ;; one call counted, not one per id — so a test can tell eager
    ;; batch pull-through apart from N lazy per-item reads
    (swap! state update :pulls inc)
    (let [{:keys [down removed docs]} @state]
      (when down (throw (ex-info "calendar feed unreachable" {})))
      (into {}
            (keep (fn [xid]
                    (when-not (contains? removed xid)
                      [xid (doc+etag
                            (or (get docs xid)
                                {:title xid :date (str (LocalDate/now))
                                 :kind "note"}))])))
            xids))))

(defn fake-events []
  (->FakeEvents (atom {:docs {} :discoverable [] :removed #{}
                       :down false :pulls 0})))

(defn seed!
  "Put an occurrence on the fake calendar (and, unless told otherwise,
  the discovery feed)."
  [fake xid {:keys [title date kind discoverable?]
             :or {kind "note" discoverable? true}}]
  (swap! (:state fake)
         (fn [s]
           (cond-> (assoc-in s [:docs xid]
                             {:title title :date (str date) :kind kind})
             discoverable? (update :discoverable
                                   #(vec (distinct (conj (vec %) xid))))))))

(defn remove!
  "Simulate the family deleting the occurrence off the calendar: the
  next pull fails, exactly like a real feed no longer carrying it."
  [fake xid]
  (swap! (:state fake)
         (fn [s]
           (-> s
               (update :docs dissoc xid)
               (update :discoverable (fn [d] (vec (remove #{xid} d))))
               (update :removed conj xid)))))

(defn down!
  [fake down?]
  (swap! (:state fake) assoc :down down?))

(defn pulls [fake]
  (:pulls @(:state fake)))

(defn reset-events!
  [fake]
  (reset! (:state fake) {:docs {} :discoverable [] :removed #{}
                         :down false :pulls 0}))
