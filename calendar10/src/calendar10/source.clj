(ns calendar10.source
  "The family calendar as a READ-WRITE Mirror adapter (waymark-6k5.1),
  over Google Calendar API v3.

  This replaces the iCal transport (mealplan10.event-source). The
  reason is not taste: a \"secret address in iCal format\" is a
  one-way GET, so the old adapter's push threw by construction and no
  declaration could have made the calendar writable. API v3 is the
  whole point of the stage.

  THE WIRE is events.list per calendar over a rolling window, with
  singleEvents=true — Google expands recurrence for us. That deletes
  the hand-rolled RFC 5545 subset the iCal path carried AND, more
  importantly, is what makes occurrences addressable: an expanded
  instance comes back with a real event id the API accepts for
  get/patch/delete, where the iCal path could only invent
  {uid}@{date} keys no authority had ever heard of. showDeleted=true
  rides along so a cancelled instance is an observed deletion (:gone)
  instead of an ambiguous absence, and the page loop is not optional —
  a truncated feed would read as a calendar-wide deletion.

  IDENTITY is \"tag:event-id\" (\"family:6bl1c9k...\"). The tag names
  which calendar the row drinks from, exactly as the confluence tags
  its sources; one calendar is configured today and the namespacing
  costs nothing, so the second one will not be a migration.

  ETAGS are Google's own per-event etags — a real version from the
  authority, not the content hash the iCal and Home Assistant sources
  are forced to synthesize. Writes ride If-Match, so a family member
  editing the event between our read and our write fails the push
  rather than silently losing their edit.

  KIND: the iCal path minted every occurrence \"note\" and recorded a
  design note asking to revisit if \"this evening is spoken for\" ever
  mattered — the feed carried no such signal. The API does:
  transparency is Google's busy/free flag. So opaque (the default for
  a timed event) reads as :blocking and transparent as :note, and the
  note is answered. mealplan's plan relation joins on :date alone
  (plan.clj:307), so nothing downstream is disturbed by kind moving.

  ALL-DAY ENDS ARE EXCLUSIVE on the wire (Google's end.date is the
  morning after) and INCLUSIVE in our document (:end_date is the last
  day the event occupies). The conversion happens once, here, in both
  directions — a fencepost this shape is worth naming rather than
  rediscovering.

  RECURRENCE, the recorded boundary: instances are writable, series
  are not. An instance carries :recurring true (Google's
  recurringEventId), and pushing one patches THAT occurrence, which
  is what Google's per-instance id already means. What this adapter
  will not do is edit the series — \"change this one\" versus \"change
  every Tuesday\" is a fork the mirror has no vocabulary for, and
  guessing wrong edits every Tuesday forever. A series-level write is
  refused by name (see push).

  THE WINDOW is 7 days back / 90 forward, inherited from the iCal
  path. A row whose date scrolls out of the window stops being
  answered by pull-many: absence there is ambiguous by protocol, so
  stored truth keeps serving with its honest synced_at. Only a
  cancelled event inside the window is :gone.

  FakeCalendar is the scriptable in-memory twin — the tests'
  instrument, offline dev's default, and what the declaration gate
  runs over so no check touches the network."
  (:require [clojure.string :as str]
            [waymark10.server.mirror :as mirror]
            [waymark10.wire :as wire])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers HttpRequest$Builder
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration Instant LocalDate OffsetDateTime ZoneId)
           (java.time.format DateTimeFormatter DateTimeParseException)))

(set! *warn-on-reflection* true)

(def api-base "https://www.googleapis.com/calendar/v3")

(def window-past-days 7)
(def window-future-days 90)

(def default-zone "America/Denver")

(def ^:private ^DateTimeFormatter rfc-3339
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssXXX"))

;; ── identity ────────────────────────────────────────────────────────

(defn xid
  "tag + the authority's event id → our external id."
  [tag id]
  (str tag ":" id))

(defn split-xid
  "\"family:6bl1c9k\" → [\"family\" \"6bl1c9k\"]. A tagless id is a
  programming error, not a feed problem — say so."
  [x]
  (let [[tag id] (str/split (str x) #":" 2)]
    (when (str/blank? (str id))
      (throw (ex-info (str "calendar id " (pr-str (str x))
                           " carries no tag:id split") {})))
    [tag id]))

;; ── translation: the authority's event → our document ───────────────

(defn- ->zone ^ZoneId [zone]
  (ZoneId/of (str (or zone default-zone))))

(defn- instant-of
  "An RFC 3339 dateTime (Google always sends the offset) → the
  canonical instant string."
  [^String s]
  (when-not (str/blank? (str s))
    (try (str (.toInstant (OffsetDateTime/parse s)))
         (catch DateTimeParseException _
           ;; a bare Z-less local time would be a feed bug; keep the
           ;; row readable rather than dropping the whole pass
           nil))))

(defn- local-date
  "The instant's date in the household zone — what :date means, and
  what mealplan's plan overlap predicate joins on."
  [^String instant zone]
  (when instant
    (str (.toLocalDate (.atZone (Instant/parse instant) (->zone zone))))))

(defn- endpoint-date
  "One end of a Google event → [all-day? date-or-nil instant-or-nil]."
  [{:keys [date dateTime]}]
  (if (not (str/blank? (str date)))
    [true (str date) nil]
    [false nil (instant-of dateTime)]))

(defn event->doc
  "One Google event (already expanded to a single occurrence) → the
  canonical calendar document. Fields the authority owns; :calendar is
  ours (the routing tag) and rides along so a mixed calendar says
  where a row came from."
  [tag event zone]
  (let [[all-day? start-date starts-at] (endpoint-date (:start event))
        [_ end-date-excl ends-at] (endpoint-date (:end event))
        date (if all-day? start-date (local-date starts-at zone))
        end-date (cond
                   ;; Google's all-day end is the morning AFTER — the
                   ;; last occupied day is one back
                   (and all-day? (not (str/blank? (str end-date-excl))))
                   (str (.minusDays (LocalDate/parse ^String end-date-excl) 1))

                   (not all-day?) (or (local-date ends-at zone) date)
                   :else date)]
    {:title (let [s (:summary event)]
              (if (str/blank? (str s)) "Untitled" (str s)))
     :date date
     ;; a same-day event's end_date IS its date; never before it
     :end_date (if (and end-date date (neg? (compare end-date date)))
                 date
                 end-date)
     :all_day all-day?
     :starts_at starts-at
     :ends_at ends-at
     ;; Google's busy/free flag answers event.clj's recorded design
     ;; note: opaque (a timed event's default) means the evening IS
     ;; spoken for
     :kind (if (= "transparent" (:transparency event)) "note" "blocking")
     :location (some-> (:location event) str not-empty)
     :detail (some-> (:description event) str not-empty)
     :recurring (not (str/blank? (str (:recurringEventId event))))
     :calendar tag
     :source_ui_href (some-> (:htmlLink event) str not-empty)}))

(defn doc->event
  "Our document → the Google event body a write sends. Only the fields
  the authority owns; everything else (the tag, the href, the sync
  bookkeeping) is ignored by design — mirror.clj's rule for exported
  documents.

  The all-day fencepost runs the other way here: our inclusive
  :end_date becomes Google's exclusive end.date."
  [{:keys [title date end_date all_day starts_at ends_at kind location
           detail]}]
  (let [end-day (or end_date date)]
    (cond-> {:summary (or title "Untitled")
             :transparency (if (= "note" (str kind)) "transparent" "opaque")}
      location (assoc :location location)
      detail (assoc :description detail)
      all_day
      (assoc :start {:date (str date)}
             :end {:date (str (.plusDays (LocalDate/parse (str end-day)) 1))})
      (not all_day)
      (assoc :start {:dateTime (str starts_at)}
             :end {:dateTime (str (or ends_at starts_at))}))))

(defn writable!
  "A document must name a start the authority can accept — an all-day
  needs its date, a timed one its instant. Refusing here beats a 400
  from Google that reads as an outage."
  [{:keys [date all_day starts_at] :as doc}]
  (when (and all_day (str/blank? (str date)))
    (throw (ex-info "an all-day calendar event needs a date" {:document doc})))
  (when (and (not all_day) (str/blank? (str starts_at)))
    (throw (ex-info "a timed calendar event needs a start instant"
                    {:document doc})))
  doc)

;; ── the wire ────────────────────────────────────────────────────────

(defn- path-encode ^String [s]
  ;; calendar ids are often addresses (abc@group.calendar.google.com);
  ;; form encoding is right except for its space, which a path spells
  ;; %20
  (str/replace (URLEncoder/encode (str s) StandardCharsets/UTF_8) "+" "%20"))

(defn- query-string [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (name k) "="
                        (URLEncoder/encode (str v) StandardCharsets/UTF_8)))
                 params)))

(defn parse-body
  "The body, parsed — but never BEFORE the status is judged. A feed
  behind a restarting proxy answers plain-text 'Bad Gateway', and
  parsing first turns a plain 502 into a raw parse exception that no
  caller's catch expects (the bug waymark-t6s records). Here a
  non-JSON body still yields ex-info carrying the real :status."
  [^String body status]
  (try
    (some-> body not-empty wire/read-json)
    (catch Exception _
      (throw (ex-info (str "google calendar answered " status
                           " with a body that is not JSON")
                      {:status status :body body})))))

(defn- api-call!
  "One API v3 call → the parsed body (nil for 204). Non-2xx throws
  ex-info carrying :status — 404/410 for a gone event, 412 for a
  failed If-Match, anything else the caller reads as unreachable."
  [{:keys [^HttpClient client token-fn base]} method path
   {:keys [params body if-match]}]
  (let [url (str (or base api-base) path
                 (when (seq params) (str "?" (query-string params))))
        builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 20))
                    (.header "authorization" (str "Bearer " (token-fn)))
                    (.header "content-type" "application/json"))
        ^HttpRequest$Builder builder (if if-match
                                       (.header builder "if-match" if-match)
                                       builder)
        publisher (if body
                    (HttpRequest$BodyPublishers/ofString
                     (wire/write-json body) StandardCharsets/UTF_8)
                    (HttpRequest$BodyPublishers/noBody))
        req (-> builder (.method ^String method publisher) (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)]
    (when (>= status 400)
      (throw (ex-info (str "google calendar answered " status " for "
                           method " " path)
                      {:status status :body (.body resp)})))
    (parse-body (.body resp) status)))

(defn window-params
  "The rolling window as RFC 3339 bounds in the household zone.

  Spelled through an explicit formatter, not toString, for two reasons
  Google cares about and Java does not: a ZonedDateTime prints its
  region in brackets (…-06:00[America/Denver]), and BOTH it and
  ISO_OFFSET_DATE_TIME drop the seconds when they are zero
  (…T00:00-06:00) — which midnight always is. RFC 3339 requires
  them."
  [zone]
  (let [z (->zone zone)
        today (LocalDate/now z)
        bound (fn [^LocalDate d]
                (.format (.toOffsetDateTime (.atStartOfDay d z))
                         rfc-3339))]
    {:timeMin (bound (.minusDays today (long window-past-days)))
     :timeMax (bound (.plusDays today (long window-future-days)))}))

(defn- list-window!
  "Every occurrence in the window for one calendar, pages followed.
  Cancelled events ride along (showDeleted) so a deletion is an
  observation rather than an absence."
  [{:keys [zone] :as src} cal-id]
  (let [{:keys [timeMin timeMax]} (window-params zone)]
    (loop [page nil, out []]
      (let [resp (api-call! src "GET"
                            (str "/calendars/" (path-encode cal-id) "/events")
                            {:params (cond-> {:timeMin timeMin
                                              :timeMax timeMax
                                              :singleEvents true
                                              :showDeleted true
                                              :maxResults 2500}
                                       page (assoc :pageToken page))})
            items (into out (:items resp))]
        (if-some [next-page (some-> (:nextPageToken resp) str not-empty)]
          (recur next-page items)
          items)))))

(defn- cancelled? [event]
  (= "cancelled" (str (:status event))))

;; ── the adapter ─────────────────────────────────────────────────────

(defn- cal-id-for [calendars tag]
  (or (get calendars tag)
      (throw (ex-info (str "no calendar configured for tag " (pr-str tag))
                      {:tag tag :known (vec (keys calendars))}))))

(defrecord GoogleCalendar [client token-fn base calendars create-tag zone]
  mirror/MirrorAdapter
  (discover [this]
    (into []
          (mapcat (fn [[tag cal-id]]
                    (keep #(when-not (cancelled? %) (xid tag (:id %)))
                          (list-window! this cal-id))))
          calendars))

  (pull [this x]
    (let [[tag id] (split-xid x)
          cal-id (cal-id-for calendars tag)
          event (api-call! this "GET"
                           (str "/calendars/" (path-encode cal-id)
                                "/events/" (path-encode id))
                           {})]
      (when (cancelled? event)
        (throw (ex-info (str x " is cancelled on the calendar")
                        {:status 404})))
      [(event->doc tag event zone) (:etag event)]))

  (pull-many [this xs]
    (let [pairs (mapv split-xid xs)
          ;; one window fetch per calendar the batch mentions, not one
          ;; per id — the round-trip count is the number of calendars
          by-tag (into {}
                       (map (fn [tag]
                              [tag (into {}
                                         (map (juxt :id identity))
                                         (list-window! this
                                                       (cal-id-for calendars tag)))]))
                       (distinct (map first pairs)))]
      (into {}
            (keep (fn [[tag id]]
                    (when-some [event (get-in by-tag [tag id])]
                      [(xid tag id)
                       (if (cancelled? event)
                         ;; observed deletion — the honest sentinel the
                         ;; :on-gone policy reads
                         :gone
                         [(event->doc tag event zone) (:etag event)])])))
            pairs)))

  (push [this x document]
    (let [[tag id] (split-xid x)
          cal-id (cal-id-for calendars tag)
          current (api-call! this "GET"
                             (str "/calendars/" (path-encode cal-id)
                                  "/events/" (path-encode id))
                             {})]
      (when (cancelled? current)
        (throw (ex-info (str "the family removed " x
                             " from the calendar — resolve decides")
                        {:status 404})))
      ;; the recorded boundary: an occurrence is writable, a series is
      ;; not. Google's per-instance id means "this occurrence", so an
      ;; instance push is safe; a row standing for the whole series has
      ;; no per-occurrence meaning and must not be guessed at.
      (when (and (:recurrence current)
                 (str/blank? (str (:recurringEventId current))))
        (throw (ex-info (str x " is a recurring SERIES, not one of its "
                             "occurrences — editing it would move every "
                             "occurrence, which this adapter refuses")
                        {:status 409})))
      (let [updated (api-call! this "PATCH"
                               (str "/calendars/" (path-encode cal-id)
                                    "/events/" (path-encode id))
                               {:body (doc->event (writable! document))
                                ;; the etag we just read: a family
                                ;; member editing between our read and
                                ;; our write fails the push instead of
                                ;; losing their edit
                                :if-match (:etag current)})]
        (:etag updated))))

  mirror/MirrorCreateAdapter
  (push-create [this document]
    (let [tag (or (some-> (:calendar document) str not-empty) create-tag)
          cal-id (cal-id-for calendars tag)
          created (api-call! this "POST"
                             (str "/calendars/" (path-encode cal-id) "/events")
                             {:body (doc->event (writable! document))})]
      [(xid tag (:id created)) (:etag created)])))

(defn delete-event!
  "Remove the event from the calendar — NOT part of MirrorAdapter, and
  deliberately so: the mirror's push writes a document, and deletion
  is not a document. Stage 2's `cancel` action has to decide whether
  cancelling a family event means deleting it here or merely dropping
  our row, and that decision belongs with the kind, not the
  transport. Exposed now because the probe's round trip has to clean
  up after itself."
  [{:keys [calendars] :as src} x]
  (let [[tag id] (split-xid x)
        cal-id (cal-id-for calendars tag)]
    (api-call! src "DELETE"
               (str "/calendars/" (path-encode cal-id)
                    "/events/" (path-encode id))
               {})
    x))

(defn google-calendar
  "The real boundary over the family's Google Calendar.

  config: :token-fn (a zero-arg access-token source —
  calendar10.oauth/from-env), :calendars ({tag → google calendar id},
  default {\"family\" \"primary\"}), :create-tag (which calendar a
  locally-born event is created on — the first configured by default),
  :zone (the household zone local dates are read in — default
  America/Denver), :base (the API base, for tests)."
  [{:keys [token-fn calendars create-tag zone base]}]
  (let [calendars (or (not-empty calendars) {"family" "primary"})]
    (->GoogleCalendar
     (-> (HttpClient/newBuilder)
         (.connectTimeout (Duration/ofSeconds 10))
         (.build))
     token-fn
     (or base api-base)
     calendars
     (or create-tag (first (sort (keys calendars))))
     (or zone default-zone))))

(defn parse-calendars
  "\"family=primary,colton=abc@group.calendar.google.com\" → the tag →
  id map. A bare id with no tag= takes the tag \"family\", so the
  single-calendar case stays one word in the env."
  [s]
  (when-not (str/blank? (str s))
    (into {}
          (map (fn [entry]
                 (let [[a b] (str/split (str/trim entry) #"=" 2)]
                   (if (str/blank? (str b)) ["family" a] [a b]))))
          (remove str/blank? (str/split (str s) #",")))))

(defn from-env
  "The deployed calendar off CALENDAR10_* — nil when no credential is
  configured, which is offline dev's cue to use the fake."
  ([token-fn] (from-env token-fn #(System/getenv ^String %)))
  ([token-fn env]
   (when token-fn
     (google-calendar {:token-fn token-fn
                       :calendars (parse-calendars (env "CALENDAR10_CALENDARS"))
                       :create-tag (env "CALENDAR10_CREATE_CALENDAR")
                       :zone (env "CALENDAR10_ZONE")}))))

;; ── the scriptable twin ─────────────────────────────────────────────

(defn- fake-etag [state]
  (str "\"fake-" (:rev state) "\""))

(defrecord FakeCalendar [state]
  mirror/MirrorAdapter
  (discover [_]
    (let [{:keys [down events]} @state]
      (when down (throw (ex-info "calendar unreachable" {})))
      (into [] (comp (remove (comp :cancelled val)) (map key)) events)))

  (pull [_ x]
    (swap! state update :pulls inc)
    (let [{:keys [down events] :as s} @state]
      (when down (throw (ex-info "calendar unreachable" {})))
      (let [entry (get events x)]
        (when (or (nil? entry) (:cancelled entry))
          (throw (ex-info (str x " is not on the calendar") {:status 404})))
        [(:doc entry) (or (:etag entry) (fake-etag s))])))

  (pull-many [_ xs]
    ;; one call counted, not one per id — so a test can tell the eager
    ;; batch pull-through apart from N lazy first reads
    (swap! state update :pulls inc)
    (let [{:keys [down events] :as s} @state]
      (when down (throw (ex-info "calendar unreachable" {})))
      (into {}
            (keep (fn [x]
                    (when-some [entry (get events x)]
                      [x (if (:cancelled entry)
                           :gone
                           [(:doc entry) (or (:etag entry) (fake-etag s))])])))
            xs)))

  (push [_ x document]
    (swap! state update :pushes inc)
    (let [{:keys [down push-fail events]} @state]
      (when down (throw (ex-info "calendar unreachable" {})))
      (when push-fail
        (throw (ex-info (if (string? push-fail)
                          push-fail
                          "the event changed under our push")
                        {})))
      (let [entry (get events x)]
        (when (or (nil? entry) (:cancelled entry))
          (throw (ex-info (str x " is not on the calendar") {:status 404}))))
      (writable! document)
      (let [s (swap! state
                     (fn [s]
                       (let [rev (inc (:rev s))]
                         (-> s
                             (assoc :rev rev)
                             (assoc-in [:events x]
                                       {:doc document
                                        :etag (str "\"fake-" rev "\"")})))))]
        (get-in s [:events x :etag]))))

  mirror/MirrorCreateAdapter
  (push-create [_ document]
    (swap! state update :pushes inc)
    (let [{:keys [down push-fail]} @state]
      (when down (throw (ex-info "calendar unreachable" {})))
      (when push-fail
        (throw (ex-info (if (string? push-fail)
                          push-fail
                          "the calendar refused the create")
                        {})))
      (writable! document)
      (let [s (swap! state update :rev inc)
            tag (or (some-> (:calendar document) str not-empty) "family")
            x (xid tag (str "born-" (:rev s)))
            etag (fake-etag s)]
        (swap! state assoc-in [:events x]
               {:doc (assoc document :calendar tag) :etag etag})
        [x etag]))))

(def ^:private fresh-state
  {:events {} :rev 0 :pulls 0 :pushes 0 :down false :push-fail false})

(defn fake-calendar [] (->FakeCalendar (atom fresh-state)))

(defn seed!
  "Put an event on the fake calendar. doc keys are the canonical
  document's; only :title and :date are required."
  [fake x doc]
  (swap! (:state fake)
         (fn [s]
           (-> s
               (update :rev inc)
               (assoc-in [:events x]
                         {:doc (merge {:kind "note" :all_day true
                                       :calendar (first (split-xid x))}
                                      doc)
                          :etag (str "\"fake-" (inc (:rev s)) "\"")}))))
  x)

(defn cancel!
  "Simulate the family deleting the event: discovery drops it, pull
  fails, and pull-many answers :gone — the observed deletion."
  [fake x]
  (swap! (:state fake) assoc-in [:events x :cancelled] true))

(defn down! [fake down?]
  (swap! (:state fake) assoc :down down?))

(defn fail-pushes!
  "Script the write seam: truthy makes every push and create throw
  (pass a string for the exact sentence); false restores success."
  [fake failing]
  (swap! (:state fake) assoc :push-fail failing))

(defn pulls [fake] (:pulls @(:state fake)))
(defn pushes [fake] (:pushes @(:state fake)))
(defn stored [fake x] (get-in @(:state fake) [:events x :doc]))
(defn reset-calendar! [fake] (reset! (:state fake) fresh-state))
