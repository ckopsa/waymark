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

  Recorded scope (batch E closes mealplan10's biggest deviation from
  mealplan9): the iCal parse is line-unfolding + VEVENT extraction in
  plain Clojure, and recurring VEVENTs now EXPAND to per-date
  occurrences within the sync window through a pure expander
  (expand-rrule) — the semantics mealplan9 leaned on
  recurring_ical_events for, ported for the profile real family
  calendars use: FREQ=DAILY/WEEKLY/MONTHLY with INTERVAL, BYDAY
  (weekly), UNTIL/COUNT, EXDATE. An event whose rule uses anything
  else (BYSETPOS, FREQ=YEARLY, positional BYDAY, RDATE, …) is skipped
  WHOLE with a *err* warning naming the rule — a recorded boundary,
  never a crash and never a silent partial expansion. Bad DTSTART
  years (observed: year 1 in real feeds) are dropped per-event, never
  the whole feed.

  FakeEvents is the scriptable in-memory twin — the tests' instrument
  and the offline-dev default (as FakeFunds is to WarehouseFunds); it
  also implements the batch-E push seam with a scriptable failure
  switch, and seed-recurring! drives the SAME expander so family-week
  tests can put a weekly recital on the fake calendar."
  (:require [clojure.string :as str]
            [waymark10.server.mirror :as mirror]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time DayOfWeek Duration LocalDate)))

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
  (property parameters stripped: DTSTART;VALUE=DATE → DTSTART).
  EXDATE alone accumulates into a vector — a series may carry one
  line per excluded date."
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
              (recur (rest lines)
                     (if (= "EXDATE" k)
                       (update current k (fnil conj []) v)
                       (assoc current k v))
                     out))))
        :else (recur (rest lines) current out))
      out)))

(defn- dtstart-date
  "A DTSTART value's date part: 20260714 or 20260714T183000Z →
  LocalDate; nil when unparseable or outside the sane-year bound."
  [^String v]
  (when (and v (<= 8 (count v)))
    (let [ymd (subs v 0 8)]
      (try
        (let [d (LocalDate/parse ymd java.time.format.DateTimeFormatter/BASIC_ISO_DATE)]
          (when (<= min-sane-year (.getYear d) max-sane-year)
            d))
        (catch Exception _ nil)))))

;; ── RRULE expansion (batch E, mealplan9's recurring_ical_events
;;    semantics for the profile real family calendars use) ───────────

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "mealplan10 events: " parts))))

(def ^:private day-codes
  {"MO" DayOfWeek/MONDAY "TU" DayOfWeek/TUESDAY "WE" DayOfWeek/WEDNESDAY
   "TH" DayOfWeek/THURSDAY "FR" DayOfWeek/FRIDAY "SA" DayOfWeek/SATURDAY
   "SU" DayOfWeek/SUNDAY})

(defn- parse-rrule [^String s]
  (into {}
        (keep (fn [^String part]
                (let [i (.indexOf part "=")]
                  (when (pos? i)
                    ;; the whole grammar is case-insensitive (values are
                    ;; day codes, numbers and timestamps — all safe to fold)
                    [(str/upper-case (subs part 0 i))
                     (str/upper-case (subs part (inc i)))]))))
        (str/split (str/trim s) #";")))

(def ^:private supported-parts
  #{"FREQ" "INTERVAL" "COUNT" "UNTIL" "BYDAY" "BYMONTHDAY" "WKST"})

(defn- rrule-candidates
  "The rule's full recurrence grid from DTSTART, ascending and lazy —
  the profile checks below guarantee the case is total."
  [^LocalDate dtstart freq interval byday bymonthday]
  (let [interval (long interval)]
   (case freq
    "DAILY" (iterate #(.plusDays ^LocalDate % interval) dtstart)
    "WEEKLY"
    (if (seq byday)
      ;; the BYDAY grid over weeks-from-DTSTART's-week (WKST=MO, the
      ;; default — anything else on a multi-week interval is refused
      ;; as unsupported before we get here)
      (let [week0 (.minusDays dtstart
                              (long (dec (.getValue (.getDayOfWeek dtstart)))))
            days (sort-by #(.getValue ^DayOfWeek %) byday)]
        (filter #(not (.isBefore ^LocalDate % dtstart))
                (mapcat (fn [k]
                          (let [monday (.plusWeeks week0 (* (long k) interval))]
                            (map #(.plusDays monday
                                             (long (dec (.getValue ^DayOfWeek %))))
                                 days)))
                        (range))))
      (iterate #(.plusWeeks ^LocalDate % interval) dtstart))
    "MONTHLY"
    ;; by day-of-month (DTSTART's, or a single BYMONTHDAY); months
    ;; without that day are skipped (RFC 5545's rule)
    (let [dom (long (or bymonthday (.getDayOfMonth dtstart)))]
      (filter #(not (.isBefore ^LocalDate % dtstart))
              (keep (fn [k]
                      (let [m (.plusMonths (.withDayOfMonth dtstart 1)
                                           (* (long k) interval))]
                        (when (<= dom (.lengthOfMonth m))
                          (.withDayOfMonth m dom))))
                    (range)))))))

(defn expand-rrule
  "One recurring VEVENT's dates within [lo hi] (both inclusive):
  → {:dates [LocalDate …]} for the supported profile —
  FREQ=DAILY/WEEKLY/MONTHLY, INTERVAL, BYDAY (weekly, plain two-letter
  codes), a single BYMONTHDAY (monthly), UNTIL (inclusive) or COUNT
  (counted from DTSTART over the whole set, BEFORE the window filter
  and BEFORE EXDATE removal — RFC 5545's set semantics) — or
  {:unsupported \"part\"} naming the first part outside the profile
  (BYSETPOS, FREQ=YEARLY, positional BYDAY like 2TU, RDATE is checked
  by the caller, …). Pure over its arguments; exdates is a set/coll
  of LocalDates. Recorded simplification: occurrences follow the rule
  grid — a DTSTART off its own BYDAY grid (which real calendars don't
  emit) contributes no extra occurrence."
  [^LocalDate dtstart ^String rrule-str exdates ^LocalDate lo ^LocalDate hi]
  (let [rule (parse-rrule rrule-str)
        freq (get rule "FREQ")
        interval (if-some [iv (get rule "INTERVAL")] (parse-long iv) 1)
        count-n (when-some [c (get rule "COUNT")] (parse-long c))
        until (when-some [u (get rule "UNTIL")] (dtstart-date u))
        byday (when-some [bd (get rule "BYDAY")]
                (mapv #(get day-codes %) (str/split bd #",")))
        bymonthday (when-some [md (get rule "BYMONTHDAY")]
                     (when-not (str/includes? md ",") (parse-long md)))
        unknown (first (sort (remove supported-parts (keys rule))))
        unsupported
        (cond
          unknown unknown
          (not (contains? #{"DAILY" "WEEKLY" "MONTHLY"} freq))
          (str "FREQ=" freq)
          (or (nil? interval) (not (pos? interval)))
          (str "INTERVAL=" (get rule "INTERVAL"))
          (and (get rule "BYDAY") (not= "WEEKLY" freq))
          (str "BYDAY with FREQ=" freq)
          (and byday (some nil? byday))
          (str "BYDAY=" (get rule "BYDAY"))
          (and (get rule "BYMONTHDAY") (not= "MONTHLY" freq))
          (str "BYMONTHDAY with FREQ=" freq)
          (and (get rule "BYMONTHDAY")
               (or (nil? bymonthday) (not (<= 1 bymonthday 31))))
          (str "BYMONTHDAY=" (get rule "BYMONTHDAY"))
          (and (get rule "UNTIL") (nil? until))
          (str "UNTIL=" (get rule "UNTIL"))
          (and (get rule "COUNT") (or (nil? count-n) (not (pos? count-n))))
          (str "COUNT=" (get rule "COUNT"))
          (and (not= "MO" (get rule "WKST" "MO")) (= "WEEKLY" freq)
               (< 1 (long interval)))
          (str "WKST=" (get rule "WKST") " on INTERVAL=" interval))]
    (if unsupported
      {:unsupported unsupported}
      (let [candidates (rrule-candidates dtstart freq (long interval)
                                         byday bymonthday)
            bounded (cond
                      count-n (take count-n candidates)
                      until (take-while #(not (.isAfter ^LocalDate % until))
                                        candidates)
                      :else candidates)
            ex (set exdates)]
        {:dates (into []
                      (comp (take-while #(not (.isAfter ^LocalDate % hi)))
                            (remove #(.isBefore ^LocalDate % lo))
                            (remove ex))
                      bounded)}))))

(defn- exdates-of [ev]
  (into #{}
        (comp (mapcat #(str/split % #","))
              (keep dtstart-date))
        (get ev "EXDATE" [])))

(defn- vevent-dates
  "One parsed VEVENT → the LocalDates it occupies within [lo hi]: the
  single DTSTART date for a plain event, the expanded set for a
  recurring one. An unsupported rule (or an RDATE, which ADDS
  occurrences we would silently lose) skips THAT event with a *err*
  warning naming it — recorded boundary, never a crash."
  [ev ^LocalDate lo ^LocalDate hi]
  (when-some [^LocalDate d (dtstart-date (get ev "DTSTART"))]
    (cond
      (get ev "RDATE")
      (do (warn! "skipping event " (get ev "UID" "no-uid")
                 " — RDATE (extra occurrences) is unsupported")
          nil)

      (nil? (get ev "RRULE"))
      (when (and (not (.isBefore d lo)) (not (.isAfter d hi)))
        [d])

      :else
      (let [res (expand-rrule d (get ev "RRULE") (exdates-of ev) lo hi)]
        (if-some [part (:unsupported res)]
          (do (warn! "skipping recurring event " (get ev "UID" "no-uid")
                     " — unsupported RRULE part " part
                     " in \"" (get ev "RRULE") "\"")
              nil)
          (:dates res))))))

(defn feed-occurrences
  "An iCal body → {external-id doc} for every occurrence within
  [lo hi] — the pure heart of the adapter, recurring VEVENTs expanded
  per date, each occurrence's external_id {uid}@{date} (waymark9's
  identity scheme: one VEVENT's occurrences are distinct mirrored
  resources, each with its own date). Every occurrence is minted kind
  \"note\" — the feed carries no signal for \"this evening is spoken
  for\"."
  [^String ics-body ^LocalDate lo ^LocalDate hi]
  (into {}
        (mapcat (fn [ev]
                  (let [uid (get ev "UID" "no-uid")
                        title (get ev "SUMMARY" "Untitled")]
                    (for [d (vevent-dates ev lo hi)]
                      [(str uid "@" d)
                       {:title title :date (str d) :kind "note"}]))))
        (vevents (unfold-lines ics-body))))

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
  "One feed fetch → {external-id doc} for every occurrence (recurring
  series expanded) in the rolling window. The feed is small (a
  personal calendar), so fetching whole and filtering in memory beats
  any partial-fetch scheme."
  [ics-url]
  (let [today (LocalDate/now)]
    (feed-occurrences (fetch-ics ics-url)
                      (.minusDays today (long window-past-days))
                      (.plusDays today (long window-future-days)))))

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
            xids)))
  (push [_ _xid _document]
    ;; the calendar is the family's, never ours to write back — the
    ;; event kind stays pull-only (no :push-on-write), so this is
    ;; never called; throwing keeps the boundary honest if it ever is
    (throw (ex-info "the family calendar is read-only — events are never pushed"
                    {}))))

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
            xids)))
  (push [_ xid document]
    ;; the batch-E write-back seam, scriptable: fail-pushes! makes the
    ;; next pushes throw (a conflict/outage, the caller can't tell —
    ;; by design, at this scope); success lands the document on the
    ;; fake calendar and answers its content etag, like a real write
    (swap! state update :pushes (fnil inc 0))
    (let [{:keys [down push-fail]} @state]
      (when down (throw (ex-info "calendar feed unreachable" {})))
      (when push-fail
        (throw (ex-info (if (string? push-fail)
                          push-fail
                          "external document changed under our push")
                        {})))
      (let [doc {:title (:title document)
                 :date (str (:date document))
                 :kind (or (:kind document) "note")}]
        (swap! state
               (fn [s]
                 (-> s
                     (assoc-in [:docs xid] doc)
                     (update :discoverable
                             #(vec (distinct (conj (vec %) xid))))
                     (update :removed disj xid))))
        (content-etag doc)))))

(defn fake-events []
  (->FakeEvents (atom {:docs {} :discoverable [] :removed #{}
                       :down false :pulls 0 :pushes 0 :push-fail false})))

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

(defn seed-recurring!
  "Put a recurring series on the fake calendar through the SAME pure
  expander the real feed parser uses: one seeded (discoverable)
  occurrence per date the rule lands in the window → the seeded
  external ids ({uid}@{date}), in date order. opts: :title, :rrule
  (the raw RRULE string), :dtstart, optional :exdates (dates), :kind
  (default \"note\"), :from/:to (the window; defaults to the real
  adapter's rolling window around today). An unsupported rule throws
  — a test seeding one deserves the loud no, not a silent empty
  calendar."
  [fake uid {:keys [title rrule dtstart exdates kind from to]
             :or {kind "note"}}]
  (let [->date (fn [d] (if (instance? LocalDate d)
                         d
                         (LocalDate/parse (str d))))
        today (LocalDate/now)
        lo (if from (->date from)
               (.minusDays today (long window-past-days)))
        hi (if to (->date to)
               (.plusDays today (long window-future-days)))
        res (expand-rrule (->date dtstart) rrule
                          (into #{} (map ->date) exdates) lo hi)]
    (when-some [part (:unsupported res)]
      (throw (ex-info (str "unsupported RRULE part " part)
                      {:rrule rrule})))
    (mapv (fn [d]
            (let [xid (str uid "@" d)]
              (seed! fake xid {:title title :date d :kind kind})
              xid))
          (:dates res))))

(defn down!
  [fake down?]
  (swap! (:state fake) assoc :down down?))

(defn fail-pushes!
  "Script the push seam: truthy makes every push throw (pass a string
  for the exact failure sentence); false restores success."
  [fake failing]
  (swap! (:state fake) assoc :push-fail failing))

(defn pulls [fake]
  (:pulls @(:state fake)))

(defn pushes [fake]
  (:pushes @(:state fake) 0))

(defn reset-events!
  [fake]
  (reset! (:state fake) {:docs {} :discoverable [] :removed #{}
                         :down false :pulls 0 :pushes 0 :push-fail false}))
