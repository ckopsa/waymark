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
  (:import (java.time LocalDate ZoneOffset)))

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
               [:maybe [:enum "chore" "meal" "todo"]]]
              [:assignee {:optional true :filter #{:eq}}
               [:maybe [:waymark/vocab {:open true}]]]
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
     ;; names the authority; only "todo" takes births (the waymark
     ;; engines' rows are born of their own law), and unsaid it
     ;; defaults there — capture should cost one field.
     ;; TWO DUE AFFORDANCES, ONE CANONICAL FACT: the birth door
     ;; offers a day (:due_date — "sometime Tuesday") and a clock
     ;; time (:due_at); both populate the one :due_at instant the
     ;; law, the sort, and every source compare — a day widens to
     ;; its closing midnight at birth and the input field never
     ;; persists. Naming both refuses (one-due): the door stays
     ;; honest instead of silently preferring.
     :create-schema [:map
                     [:title [:string {:min 1 :max 200}]]
                     [:source {:optional true} [:maybe [:enum "todo"]]]
                     [:due_at {:optional true
                               :x-display {:label "Due by (clock time)"}}
                      [:maybe :waymark/instant]]
                     [:due_date {:optional true
                                 :x-display {:label "Due day (all day — becomes its closing midnight)"}}
                      [:maybe :waymark/date]]
                     [:detail {:optional true} [:maybe [:string {:max 1000}]]]]
     :create-guards [one-due]
     :on-create (fn [row _ctx]
                  (let [d (get-in row [:data :due_date])]
                    (-> row
                        (update :data dissoc :due_date)
                        (update-in [:data :due_at] #(or % (some-> d day-end)))
                        (update-in [:data :source] #(or % "todo"))
                        (update-in [:data :status] #(or % "open")))))
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
