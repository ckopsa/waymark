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
  (:require [waymark10.dsl :refer [defguard defhandler refuse resource]]
            [waymark10.server.mirror :as mirror]))

;; a household queue: due times matter within minutes, not seconds
(def ttl-seconds 300)
(def discover-every 300)

;; local writes move between the writable sync states (the machine
;; owns conflicted; resolve_conflict is the way back)
(def ^:private writable #{:fresh :stale :unreachable})

(defguard not-dropped
  (refuse "A task the source dropped does not complete — the authority already let it go.")
  '(not= (var :status) "dropped"))

(defhandler mark-done [row _inp _ctx]
  (assoc-in row [:data :status] "done"))

(defhandler set-priority [row inp _ctx]
  (assoc-in row [:data :priority] (:priority inp)))

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
               [:maybe [:enum "chore" "meal"]]]
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
               [:maybe [:string {:max 1000}]]]]
     :filterable {:state #{:eq :in}}
     :display {:title "{data.title}"}
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
       :input [:map [:priority {:x-display {:label "Rank (lower ranks sooner)"}}
                     [:int {:min 0}]]]
       :edit {:prefill [:priority]}
       :safety {:idempotent true :reversible false :confirm false
                :one-way "Nothing is lost — a new rank overwrites this one; prioritize again to change it."}
       :handler set-priority
       :display {:label "Prioritize" :order 2}}}}
    {:adapter adapter
     :ttl-seconds ttl-seconds
     :discover-every discover-every
     :push-on-write true
     :document :partial})))
