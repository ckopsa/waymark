(ns calendar10.resources.event
  "The family calendar Event: a READ-WRITE Mirror of Google Calendar
  (waymark-6k5.2), and the whole of the :calendar domain.

  Every mealplan before this kept the calendar out of the system
  because the plan couldn't own it — a recital doesn't belong to a
  meal plan, it merely OVERLAPS one. That reasoning still holds and is
  exactly why the kind now lives HERE rather than under :meals: the
  calendar is its own domain, cited by the meal plan through a
  date-containment predicate, owned by neither.

  What changed from the pull-only era is authority, not ownership. The
  calendar is still the family's — but waymark is a member of the
  family, so it may add to it, move what it added, and say so out
  loud. :push-on-write carries a reschedule back; :create-push lets a
  row be BORN here, the authority minting the id claim_external stamps
  home. The iCal transport could express neither (a secret feed URL is
  a one-way GET); calendar10.source over API v3 expresses both.

  :document :partial IS DELIBERATE. Google carries far more per event
  than this kind models — attendees, reminders, conferencing,
  organizer, colour — and under the default :whole contract every sync
  would read their absence from OUR document as an instruction to
  clear them. :partial reads absence as silence. This is the same
  shield task's :priority uses, for the same reason.

  KIND IS OBSERVED, NOT AUTHORED. The old declaration minted every
  occurrence \"note\" and recorded a design note: the feed carried no
  signal for \"this evening is spoken for\". The API does — Google's
  transparency flag — so the transport maps opaque to :blocking and
  transparent to :note, and the note is closed. mealplan's plan
  relation joins on :date alone, so the meal week is undisturbed.

  DATES ARE INCLUSIVE HERE. :end_date is the last day the event
  occupies. Google's wire end is the morning after; the fencepost is
  converted inside calendar10.source, once, in both directions — do
  not re-convert it in this file.

  ONE MORE THING THE WINDOW DOES: Google answers with events that
  OVERLAP the sync window, so a multi-day event that began before it
  is discovered anyway. A row's :date may therefore sit earlier than
  the window's near edge — filters must not assume otherwise.

  RECURRENCE: an occurrence carries :recurring true and reschedules
  like any other row, because Google's per-instance id already means
  \"this occurrence\". A row standing for a whole SERIES refuses its
  push at the transport — moving it would move every occurrence, and
  \"change this one\" versus \"change every Tuesday\" is a fork this
  kind has no vocabulary for."
  (:require [waymark10.dsl :refer [defguard defguardfn defhandler one-of
                                   refuse resource]]
            [waymark10.server.mirror :as mirror]
            [waymark10.types :as t]))

;; The family edits from their phones, and a stale row's etag is what
;; a push rides — so the pull-through window is tighter than the iCal
;; era's hour. Discovery keeps the same cadence: a new event is not
;; urgent, a moved one is.
(def ttl-seconds 900)
(def discover-every 900)

;; local writes move between the writable sync states (the machine
;; owns conflicted; resolve_conflict is the way back)
(def ^:private writable #{:fresh :stale :unreachable})

;; a create guard judges the birth INPUT (the row is nil at the door),
;; so these are guard FNs, not exprs over row facts — and they answer
;; (t/allow)/(t/deny), never a bare boolean, which the door would read
;; as consent. Without them a when-less birth still fails, but LATE:
;; the transport's own refusal arrives at push time and the row lands
;; conflicted, which is a worse way to learn you forgot the date.
(defguardfn one-when
  {:judges [:all_day :date :starts_at]
   :explain "Say when: an all-day event names its date, a timed one its start."}
  [_row inp _ctx]
  (if (if (:all_day inp) (some? (:date inp)) (some? (:starts_at inp)))
    (t/allow)
    (t/deny)))

(defguardfn ends-after-it-starts
  {:judges [:all_day :date :end_date :starts_at :ends_at]
   :explain "An event cannot end before it begins."}
  [_row inp _ctx]
  (let [[a b] (if (:all_day inp)
                [(:date inp) (:end_date inp)]
                [(:starts_at inp) (:ends_at inp)])]
    (if (or (nil? a) (nil? b) (not (neg? (compare (str b) (str a)))))
      (t/allow)
      (t/deny))))

;; CANCEL IS LIMITED TO WHAT WE PUT THERE (the user's call, 2026-07-25:
;; "if it was created from our app, it's okay to cancel"). Deleting a
;; family member's own event out from under them is the failure that
;; matters here, and it is not worth the convenience.
;;
;; The origin marker is trustworthy rather than clever. A discovery
;; mint is born WITH its external_id ({:external_id xid} — mirror.clj's
;; create-mints!, which also runs NO app create guards); a local birth
;; is the only row that reaches :on-create without one, because the
;; authority has not minted it yet. So "had no external_id at birth"
;; means "we made this". :document :partial then protects the flag: a
;; pulled document never carries :born_here, and absence is silence,
;; so no sync can quietly clear it.
(defguard ours-to-cancel
  (refuse "This event came from the family's calendar, not from here — cancel it in Google Calendar.")
  '(= (var :born_here) true))

(defhandler strike
  [row _inp _ctx]
  ;; the deletion is NOT performed here: a handler runs before the
  ;; commit, and an event deleted for a write that then rolls back is
  ;; gone for good. Marking the document lets the post-commit push
  ;; pass carry it, and the adapter translates :cancelled into the
  ;; authority's own verb — the same shape confluence/push-plan uses
  ;; to turn a document into "complete".
  (assoc-in row [:data :cancelled] true))

(defhandler move
  [row inp _ctx]
  ;; only the fields the input actually names — reschedule is a move,
  ;; not a re-authoring, and a nil here would blank a real value
  (update row :data
          (fn [d]
            (reduce (fn [acc k]
                      (if (contains? inp k) (assoc acc k (get inp k)) acc))
                    d
                    [:all_day :date :end_date :starts_at :ends_at]))))

(def ^:private when-fields
  [[:all_day {:optional true :x-display {:label "All day"}} [:maybe :boolean]]
   [:date {:optional true :x-display {:label "Day"}} [:maybe :waymark/date]]
   [:end_date {:optional true :x-display {:label "Last day (same day if left blank)"}}
    [:maybe :waymark/date]]
   [:starts_at {:optional true :x-display {:label "Starts"}}
    [:maybe :waymark/instant]]
   [:ends_at {:optional true :x-display {:label "Ends"}}
    [:maybe :waymark/instant]]])

(defn event-resource
  "The event kind over one calendar adapter — calendar10.source's
  fake-calendar for tests, offline dev and the declaration gate;
  google-calendar when the credential is configured."
  [adapter]
  (resource
   (mirror/declaration
    {:kind :event
     :domain :calendar
     :summary "{data.title} · {data.date}"
     :label-template "{data.title}"
     :schema (into
              [:map
               [:title {:optional true} [:maybe [:string {:max 200}]]]
               ;; the plan's overlap predicate joins on :date — it must
               ;; stay promoted and filterable, whatever else changes
               [:date {:optional true :filter #{:eq :range} :sort :default}
                [:maybe :waymark/date]]
               [:end_date {:optional true :filter #{:range}}
                [:maybe :waymark/date]]
               [:all_day {:optional true :filter #{:eq}} [:maybe :boolean]]
               [:starts_at {:optional true :filter #{:after}}
                [:maybe :waymark/instant]]
               [:ends_at {:optional true} [:maybe :waymark/instant]]
               ;; observed from Google's busy/free flag, never authored
               ;; here — see the namespace note
               [:kind {:optional true :filter #{:eq :in}
                       :x-display {:showcase true}}
                [:maybe (one-of :blocking :note)]]
               [:location {:optional true} [:maybe [:string {:max 200}]]]
               [:detail {:optional true :x-display {:widget "prose"}}
                [:maybe [:string {:max 2000}]]]
               ;; an occurrence of a series: reschedulable, but the
               ;; series itself is not ours to move
               [:recurring {:optional true :filter #{:eq}} [:maybe :boolean]]
               ;; which calendar this row drinks from — the transport's
               ;; routing tag, and what a second calendar would filter on
               [:calendar {:optional true :filter #{:eq :in}}
                [:maybe [:string {:max 64}]]]
               [:source_ui_href {:optional true :x-display {:hidden true}}
                [:maybe [:string {:max 1000}]]]
               ;; waymark put this on the calendar, so waymark may take
               ;; it off again — see ours-to-cancel for why the marker
               ;; is birth-without-an-external_id
               [:born_here {:optional true :filter #{:eq}
                            :x-display {:hidden true}}
                [:maybe :boolean]]
               ;; the tombstone the push pass reads as "delete it"
               [:cancelled {:optional true :filter #{:eq}
                            :x-display {:hidden true}}
                [:maybe :boolean]]]
              nil)
     :filterable {:state #{:eq :in}}
     :display {:title "{data.title} — {data.date}"}
     ;; the way back to the authority: a real browser hop to the event
     ;; in Google Calendar, anchored on the row
     :links [{:rel "origin" :href "{data.source_ui_href}" :external true
              :summary "This event in Google Calendar, where the family keeps it"}]

     ;; ── the birth: scheduling something onto the family calendar ──
     ;; an ordinary create! whose post-commit push is a CREATE — Google
     ;; mints the id, claim_external stamps it home
     :create-schema (into [:map
                           [:title {:x-display
                                    {:label "What is happening"
                                     :help "How it should read on the family calendar — \"Iris recital\", \"dentist, Otto\"; this is the line everyone sees on their phone."}}
                            [:string {:min 1 :max 200}]]]
                          (concat
                           when-fields
                           [[:location {:optional true
                                        :x-display
                                        {:label "Where"
                                         :help "Enough for whoever is driving — the school gym, the address, \"their place\"; left blank the event simply names no place."}}
                             [:maybe [:string {:max 200}]]]
                            [:detail {:optional true
                                      :examples ["Doors at 6:30, she needs to be there by 6:00 in the black skirt."]
                                      :x-display
                                      {:widget "prose"
                                       :label "Anything else to know"
                                       :help "The part that would otherwise be a text message — what to bring, when to leave, who is picking up."}}
                             [:maybe [:string {:max 2000}]]]
                            [:kind {:optional true
                                    :x-display
                                    {:label "Does this claim the evening"
                                     :choices {"blocking" "Spoken for — we are not free then"
                                               "note" "Just a note — worth knowing, not a claim on anyone"}}}
                             [:maybe (one-of :blocking :note)]]
                            [:calendar {:optional true
                                        :x-display
                                        {:label "Which calendar"
                                         :help "Which of the household's calendars it lands on — left blank it goes on the family one, which is nearly always what is meant."}}
                             [:maybe [:string {:max 64}]]]]))
     :create-guards [one-when ends-after-it-starts]
     :on-create (fn [row _ctx]
                  (update row :data
                          (fn [d]
                            (-> d
                                ;; born without an external id ⇒ this
                                ;; is OUR row, not a discovery mint
                                (assoc :born_here
                                       (nil? (:external_id d)))
                                (update :all_day #(if (some? %) % true))
                                ;; a one-day event names its day once
                                (update :end_date #(or % (:date d)))
                                ;; ours by default; the transport routes
                                ;; the create by this tag
                                (update :calendar #(or % "family"))
                                ;; waymark's own additions are notes
                                ;; unless the author says otherwise —
                                ;; blocking is a claim on the evening
                                (update :kind #(or % "note"))))))

     :actions
     {:reschedule
      {:from writable :to :fresh
       :input (into [:map] when-fields)
       :guards [ends-after-it-starts]
       ;; not reversible in the machine's sense — there is no
       ;; transition back — but the move is not destructive either:
       ;; the honest sentence is that it lands on the family's real
       ;; calendar at once, and the way back is another reschedule
       :safety {:idempotent true :reversible false :confirm false
                :one-way "The new time lands on the family's calendar as soon as this saves — reschedule again to move it back."}
       :handler move
       :display {:label "Reschedule" :style :primary :order 1}
       ;; the form opens on what the row already says, so a move is an
       ;; edit and not a re-typing
       :edit {:prefill [:all_day :date :end_date :starts_at :ends_at]}}

      :cancel
      {:from writable :to :fresh
       :guards [ours-to-cancel]
       ;; :to :fresh reads oddly for a row we just deleted, but it is
       ;; honest: the sync machine says "our row agrees with the
       ;; authority", and it does — we asked for the deletion and it
       ;; happened. The domain fact lives in data (:cancelled), which
       ;; is the mirror's rule.
       :safety {:idempotent true :reversible false :confirm true
                :consequence "The event is deleted from the family's calendar — everyone who can see the calendar loses it. Putting it back means scheduling it again."}
       :handler strike
       :display {:label "Cancel" :style :danger :order 2}}}}

    {:adapter adapter
     :ttl-seconds ttl-seconds
     :discover-every discover-every
     :push-on-write true
     :create-push true
     ;; Google carries far more per event than we model — absence in
     ;; OUR document is silence, never an instruction to clear
     :document :partial})))

(defn resources
  "The :calendar domain's kinds. One today; the domain is the point."
  [adapter]
  [(event-resource adapter)])
