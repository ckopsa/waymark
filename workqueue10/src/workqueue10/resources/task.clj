(ns workqueue10.resources.task
  "The Task resource: the family's ONE work queue — every task-like
  row from every household engine, as a single Mirror kind over the
  confluence adapter. One kind is the whole point: one list endpoint
  (GET /api/tasks?status=open, default-sorted by priority) is the
  cross-domain queue the day-board's panes could never interleave,
  and one grantable scope hands an agent the queue through the
  ordinary invite/bind/grant handshake — surfaces are neither
  fingerprinted nor grantable, a real kind is both.

  DOMAIN STATE IS DATA (the mirror rule): every source's own
  states normalize to :status open|done|dropped; the machine here is
  the sync machine. :source is the confluence's routing tag,
  stamped by the adapter — which authority this row drinks from.

  :document :partial IS THE PRIORITY'S SHIELD. The queue owns one
  fact no authority carries: :priority (lower ranks sooner, nulls
  sort last — PG's ASC default). Under the default :whole contract a
  pulled document lacking :priority would nil it every sync; :partial
  reads absence as silence — the mode mirror.clj records for exactly
  this multi-feed shape. The tradeoff, accepted: a field the
  authority genuinely unsets no longer clears here (status always
  travels, so the queue's working facts never stale).

  CAPTURE ROUTES ON :source, and the birth door's enum is the list of
  authorities that take one. Both pocket authorities do now — home
  assistant on the LAN, google in the phone — and the queue's own
  engines never will, because a chore run is born of choreplan's law
  and not of anything typed here. Unsaid still means \"todo\": adding
  a second capture target must not move anyone who never asked for
  google. A google birth may name the list it lands in (:task_list,
  a ref, because lists are rows); the guards below hold the two lines
  that naming buys — a list belongs to the authority capturing into
  it, and a due bound for google is a DAY, never a clock time.

  Two local writes:
  - complete — marking reality. The post-commit pass pushes it and
    the source boundary translates to the authority's own :complete;
    a task dropped upstream refuses locally with the guard's
    sentence; a refused push lands conflicted, resolve_conflict
    decides.
  - prioritize — the hub-local ranking. Its push is the shared
    push-plan's :noop: the boundary GETs the authority, agrees it
    has nothing to say, and answers the fresh etag — the round-trip
    doubles as a freshness check, so :to :fresh is earned, not
    asserted."
  (:require [waymark10.dsl :refer [defguard defguardfn defhandler refuse
                                   resource]]
            [waymark10.guards :as g]
            [waymark10.server.mirror :as mirror]
            [waymark10.types :as t])
  (:import (java.time Instant LocalDate ZoneOffset)))

;; a household queue: due times matter within minutes, not seconds
(def ttl-seconds 300)
(def discover-every 300)

;; local writes move between the writable sync states (the machine
;; owns conflicted; resolve_conflict is the way back)
(def ^:private writable #{:fresh :stale :unreachable})

(defguard not-dropped
  (refuse "A task the source dropped does not complete — the authority already let it go.")
  '(not= (var :status) "dropped"))

;; a create guard judges the birth INPUT (the row is nil at the
;; door), so it's a guard FN, not an expr over row facts
(defguardfn one-due
  {:judges [:due_date :due_at]
   :explain "Name one due — the day OR the clock time, not both; a day widens to its closing midnight."}
  [_row inp _ctx]
  (if (and (:due_date inp) (:due_at inp))
    (t/deny)
    (t/allow)))

(defguardfn list-owned-by-the-capturing-source
  {:judges [:task_list :source]
   :reads [:task_list]
   :vars [:owner :capturing]
   :explain "That list belongs to {owner} and this capture goes to {capturing} — a task lands in a list its own authority owns, and google's is the door that takes a named one."}
  [_row inp ctx]
  ;; naming a list is only expressible because lists became rows, and
  ;; only MEANINGFUL for the authority whose birth honours it: google
  ;; reads the named list, home assistant captures into its one
  ;; configured entity and would ignore the name without saying so.
  ;; So the pair must agree AND be google's — the alternative is a
  ;; person picking "Errands" and watching the task land somewhere
  ;; else, which is the failure mode that looks like nothing at all.
  (let [capturing (or (:source inp) "todo")]
    (if-some [ref (:task_list inp)]
      (if-some [read (:read ctx)]
        (let [row (read :task_list ref)
              owner (get-in row [:data :source])]
          (if (= "gtasks" owner capturing)
            (t/allow)
            (t/deny (cond-> {:vars {:owner (or owner "no authority we mirror")
                                    :capturing capturing}}
                      (nil? row)
                      (assoc :errors {:task_list ["task list not found"]})))))
        (t/allow))
      (t/allow))))

(defn clock-timed?
  "Does this instant name a time of DAY? Midnight UTC is how the queue
  spells a whole day — the closing-midnight law every source's dues
  widen to — so it reads as a date and nothing more, and only
  something past midnight is a clock time a person typed."
  [t]
  (when (instance? Instant t)
    (let [^Instant t t]
      (or (pos? (.getNano t))
          (not (zero? (mod (.getEpochSecond t) 86400)))))))

(defguardfn day-granular-due-for-google
  {:judges [:due_at :source]
   :explain "Google records a due DATE and throws the clock time away, so name the day in due_date instead — a time kept here would be silently rewritten to that day's closing midnight on the next pass."}
  [_row inp _ctx]
  ;; THE REFUSAL THAT COSTS SOMETHING, and the reason it is worth it.
  ;; Google's due field discards the time portion on write, confirmed
  ;; live: a task created at 14:00 comes back as the bare day. So a
  ;; clock-timed capture bound for google cannot round-trip — the
  ;; queue would hold 14:00, google would hold the day, and the next
  ;; discovery pass would rewrite the local row to the day's closing
  ;; midnight, moving the deadline ten hours without a word. Accepting
  ;; it and flooring it here would agree with google and still discard
  ;; what the person typed, so the door says no and points at the
  ;; affordance that survives the trip. A midnight :due_at is
  ;; indistinguishable from a date and passes untouched.
  (if (and (= "gtasks" (:source inp)) (clock-timed? (:due_at inp)))
    (t/deny)
    (t/allow)))

(defn- day-end
  "A day-granular due → the canonical instant: the day's closing
  midnight UTC — the chore-source law, so overdue flips the morning
  after regardless of which door the due came through."
  [d]
  (-> (LocalDate/parse (str d))
      (.plusDays 1)
      (.atStartOfDay ZoneOffset/UTC)
      (.toInstant)))

(defhandler mark-done [row _inp _ctx]
  (assoc-in row [:data :status] "done"))

(defhandler set-priority [row inp _ctx]
  (assoc-in row [:data :priority] (:priority inp)))

(defhandler clear-priority [row _inp _ctx]
  (assoc-in row [:data :priority] nil))

(defn task-resource
  [adapter]
  (resource
   (mirror/declaration
    {:kind :task
     :summary "{data.title} · {data.status}"
     :label-template "{data.title}"
     :schema [:map
              [:title {:optional true}
               [:maybe [:string {:max 200}]]]
              ;; the confluence's routing tag — which authority this
              ;; row drinks from; the enum is the tag set main wires
              [:source {:optional true :filter #{:eq :in}}
               [:maybe [:enum "chore" "meal" "todo" "gtasks"]]]
              ;; WHO, as a person and not a string. The sources speak
              ;; names ("colton", "housekeeper") — that text lands in
              ;; :assignee_name, and :assignee is its resolvable
              ;; projection: the member whose :handle the name matches.
              ;; A name with no member (the housekeeper has no account)
              ;; leaves the ref nil beside the intact text — the gap
              ;; renders, and the member's arrival heals it on the next
              ;; discovery beat.
              [:assignee_name {:optional true :filter #{:eq}
                               :x-display {:label "Assigned (as named)"}}
               [:maybe [:waymark/vocab {:open true}]]]
              [:assignee {:optional true :filter #{:eq}
                          :kind :member
                          :external-key :assignee_name
                          :match :handle
                          :x-display {:label "Assigned to"}}
               [:maybe :waymark/ref]]
              ;; WHICH LIST, as a row and not a prefix. Both
              ;; list-keeping authorities name the list a task lives
              ;; in — google inside the task's own identity, home
              ;; assistant as the entity the item hangs off — and
              ;; neither fact could be filtered, labeled or linked
              ;; while it was a string (HA spent years smuggling it
              ;; into :detail). :list_key is the authority's own key,
              ;; namespaced by the confluence, and :task_list is its
              ;; resolvable projection — the mirrored list row whose
              ;; external_id it matches. This is the assignee pattern
              ;; one field over, with the ONE difference that the
              ;; target is a mirror kind, so the default :match
              ;; (external_id) applies and no :match is spelled.
              ;; A source with no list concept (a chore run, a prep
              ;; task) leaves both unset — the gap renders.
              [:list_key {:optional true
                          :x-display {:label "List key (the authority's own)"}}
               [:maybe [:string {:max 256}]]]
              [:task_list {:optional true :filter #{:eq}
                           :kind :task_list
                           :external-key :list_key
                           :x-display {:label "List"}}
               [:maybe :waymark/ref]]
              [:due_at {:optional true :filter #{:after} :sort true
                        :x-display {:label "Due by"}}
               [:maybe :waymark/instant]]
              ;; local law over synced facts: the queue's one indexed
              ;; "past due" filter (the prep_task/chore_run pattern)
              [:overdue {:optional true :filter #{:eq}
                         :derived {:over [:due_at :now]
                                   :expr '(< (var :due_at) (var :now))}}
               [:maybe :boolean]]
              ;; every source's own states, normalized, as data
              [:status {:optional true :filter #{:eq :in}
                        :x-display {:showcase true}}
               [:maybe [:enum "open" "done" "dropped"]]]
              ;; HUB-LOCAL: the cross-domain ranking no authority
              ;; carries — :partial keeps every pull's hands off it
              [:priority {:optional true :sort :default
                          :x-display {:label "Priority (lower ranks sooner)"}}
               [:maybe [:int {:min 0}]]]
              [:detail {:optional true :x-display {:widget "prose"}}
               [:maybe [:string {:max 1000}]]]
              ;; where the row drinks from, as URLs the source client
              ;; stamps: the API envelope (a client's route) and the
              ;; source engine's ui.html anchor (a person's tap — the
              ;; :origin link below). Hidden: the LINK is the
              ;; affordance, a raw URL in the fields is noise.
              [:source_href {:optional true :x-display {:hidden true}}
               [:maybe [:string {:max 500}]]]
              [:source_ui_href {:optional true :x-display {:hidden true}}
               [:maybe [:string {:max 500}]]]]
     :filterable {:state #{:eq :in}}
     :display {:title "{data.title}"}
     ;; CAPTURE: a task born HERE, pushed to the authority that will
     ;; own it (create-push — the paydesk worksheet's door). The birth
     ;; input is deliberately small: what you'd say out loud. :source
     ;; names the authority; the two POCKET authorities take births
     ;; (the waymark engines' rows are born of their own law), and
     ;; unsaid it still defaults to "todo" — capture should cost one
     ;; field, and widening the enum must not change meaning for
     ;; anyone who never asked for google.
     ;; WHICH LIST A GOOGLE CAPTURE LANDS IN, in two layers: the
     ;; source's configured default (WORKQUEUE10_GTASKS_CAPTURE, the
     ;; home assistant precedent), and an optional :task_list ref
     ;; here that wins when given — expressible only because lists
     ;; became rows, so the picker labels them by title instead of
     ;; asking for an opaque google id. Neither, and the birth
     ;; refuses at the boundary rather than guessing which of ten
     ;; lists the household meant; that refusal is the source's,
     ;; because only the source knows what it was configured with.
     ;; TWO DUE AFFORDANCES, ONE CANONICAL FACT: the birth door
     ;; offers a day (:due_date — "sometime Tuesday") and a clock
     ;; time (:due_at); both populate the one :due_at instant the
     ;; law, the sort, and every source compare — a day widens to
     ;; its closing midnight at birth and the input field never
     ;; persists. Naming both refuses (one-due): the door stays
     ;; honest instead of silently preferring. Naming a clock time
     ;; on a google-bound birth refuses too, and for a harder reason
     ;; — see day-granular-due-for-google.
     :create-schema [:map
                     [:title [:string {:min 1 :max 200}]]
                     [:source {:optional true} [:maybe [:enum "todo" "gtasks"]]]
                     [:task_list {:optional true :kind :task_list
                                  :x-display {:label "List (google only)"}}
                      [:maybe :waymark/ref]]
                     [:due_at {:optional true
                               :x-display {:label "Due by (clock time)"}}
                      [:maybe :waymark/instant]]
                     [:due_date {:optional true
                                 :x-display {:label "Due day (all day — becomes its closing midnight)"}}
                      [:maybe :waymark/date]]
                     [:detail {:optional true} [:maybe [:string {:max 1000}]]]]
     :create-guards [one-due list-owned-by-the-capturing-source
                     day-granular-due-for-google]
     :on-create (fn [row ctx]
                  ;; a named list arrives as the ROW's id and has to
                  ;; leave as the authority's own key: :list_key is
                  ;; the field the push exports and the field the
                  ;; next pull will overwrite with the same value, so
                  ;; the birth stamps it from the list it points at
                  ;; and the two spellings agree from the first
                  ;; second rather than from the first pull.
                  (let [d (get-in row [:data :due_date])
                        listed (when-some [ref (get-in row [:data :task_list])]
                                 (when-some [read (:read ctx)]
                                   (get-in (read :task_list ref)
                                           [:data :external_id])))]
                    (-> row
                        (update :data dissoc :due_date)
                        (update-in [:data :due_at] #(or % (some-> d day-end)))
                        (update-in [:data :source] #(or % "todo"))
                        (update-in [:data :status] #(or % "open"))
                        (cond-> listed
                          (assoc-in [:data :list_key] listed)))))
     ;; the way BACK to the resource that needs work: an :external
     ;; href — a real browser hop to the owning engine's UI, anchored
     ;; on the row. A source that stamps no href (a fake, a future
     ;; authority with no web face) relates to nothing — the link
     ;; simply omits, the framework's own rule.
     :links [{:rel "origin" :href "{data.source_ui_href}" :external true
              :summary "The row this task mirrors, at the engine that owns it"}]
     :actions
     {:complete
      {:from writable :to :fresh
       :guards [not-dropped]
       :safety {:idempotent true :reversible false :confirm false
                :one-way "Done is done — the queue's record; the authority hears about it on the push."}
       :handler mark-done
       :display {:label "Done" :style :primary :order 1}}

      :prioritize
      {:from writable :to :fresh
       ;; ranking is the RANKER role's (the registry + member rows
       ;; decide WHO at runtime; this binding alone is law) — anyone
       ;; may complete, only rankers reorder the family's queue
       :guards [(g/role :ranker)]
       :input [:map [:priority {:x-display {:label "Rank (lower ranks sooner)"}}
                     [:int {:min 0}]]]
       :edit {:prefill [:priority]}
       :safety {:idempotent true :reversible false :confirm false
                :one-way "Nothing is lost — a new rank overwrites this one; prioritize again to change it."}
       :handler set-priority
       :display {:label "Prioritize" :order 2}}

      ;; the way back OUT of the ranking: hub-local like prioritize
      ;; (the push is the shared plan's :noop — a freshness check),
      ;; and its own door rather than a nullable prioritize input,
      ;; because an emptied form field that silently CLEARS a rank
      ;; is a footgun, and a "Clear priority" chip is an affordance
      :deprioritize
      {:from writable :to :fresh
       :guards [(g/role :ranker)]
       :safety {:idempotent true :reversible false :confirm false
                :one-way "The rank is let go — the task rejoins the unranked tail; prioritize ranks it again."}
       :handler clear-priority
       :display {:label "Clear priority" :order 3}}}}
    {:adapter adapter
     :ttl-seconds ttl-seconds
     :discover-every discover-every
     :push-on-write true
     :create-push true
     :document :partial
     ;; a row the feed stopped carrying (the list ANSWERED, the row
     ;; was absent) is a deletion observed: it drops — out of the
     ;; open queue, kept as record. HA has no cancelled state, so
     ;; deletion IS how a todo is dropped; a down source never
     ;; triggers this (ambiguous absence keeps stored truth).
     :on-gone {:set {:status "dropped"}}
     ;; the cadenced whole-kind heal: gone rows and translation
     ;; changes land within the window, not at the next boot
     :resync-every 900})))
