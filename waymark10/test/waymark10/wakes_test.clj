(ns waymark10.wakes-test
  "The wake consumer (docs/spec-seat.md § 12.2, R-12.22; acceptance
  cases 36 and 37).

  The seat's third way of waking. The cadence is the first and a
  person's `fire` is the second; this is a transition the seat ASKED
  to be woken by, named in `wake_on` entries — or, since R-12.24, a
  queue of them reaching a size the seat named in the same place.

  What this suite proves, in the spec's own order:

  - case 36 · a committed transition matching a `wake_on` entry fires
    the seat ONE time, the text names the row that moved, and exactly
    one POST reaches that seat's own Routine. A second match inside
    `fire_interval_seconds` does not fire: it sets `wake_pending` on
    the schedule row instead. A transition of another action, and one
    of another kind, wake nothing.
  - case 37 · a match while the seat has an OPEN SITTING fires
    nothing and sets `wake_pending` — the session already awake will
    see the row when it walks its queue. The close of that sitting
    releases exactly one fire, and that fire names NO row, so the
    session walks the whole queue it missed. The flag clears with it.
  - the walk seat's computed default: a seat that walks a queue and
    wrote no `wake_on` wakes when a row of that queue is created, and
    the row holds no `wake_on` at all (the engine writes nothing).
  - and the FILTERED walk's computed default (waymark-fp62.12): when
    the walk's scope entry carries a filter, the computed entry names
    every action of that kind under it, so the door that moves a row
    INTO the filter wakes the seat and the one that moves it out does
    not. The row still holds no `wake_on`.
  - the tick: a wake the GAP held is released once the gap has
    passed, by `sweep-pending!` — the body of the thread the module
    starts.
  - the replay: two drains over one matching transition fire once.
    At-least-once delivery must not start a second run of somebody's
    Routine.
  - the declaration: a `wake_on` entry naming an action its kind does
    not have is refused at the create door, one naming a kind this
    engine does not serve is refused at `restate`, and a gap of zero
    seconds is refused by the schema.
  - R-12.24 · the COUNT wake, the second thing a `wake_on` entry can
    be: nineteen rows in the queue wake nobody, the twentieth fires
    once and the text carries the count and no row id; a count wake
    damped by an open sitting is remembered and releases one fire
    when that sitting closes; an entry's `filter` decides WHICH rows
    are counted, so a filter the kind's default does not name counts
    a different number than the queue does; an entry with no actions
    counts on every action of its kind, and a transition entry with
    none wakes on every action too (E-5.2); and `at_least` below one
    is refused by the schema at the create door.
  - E-5.2 · a transition entry with no actions that a seat held from
    before is taken off it with the sentence \"the entry matched no
    transition and was removed\", and one written afterwards stays.
  - R-12.24 · the count wake that counts DOWN (waymark-fp62.13): an
    entry with `at_most` 0 is not woken while one matching row is
    left, and fires the moment the last one leaves the filter. Its
    text carries `at_most` and no row. An entry that names both
    sizes is refused by the schema, in the entry's own place.
  - R-12.22 · the entry's `filter` is read on a TRANSITION wake too:
    the seat that names one batch wakes on that batch's completion
    and not on another's, and the same entry with no filter wakes on
    both. The row is judged AFTER the transition committed, so the
    kind's own default filter (state=open) does not hide a row the
    completion just moved.
  - waymark-fp62.17 · the SETTLE: an entry with `settle_seconds`
    fires on the TRAILING edge. Three matches inside one minute fire
    nothing, each one moves `wake_due_at` forward, and one textless
    fire goes out a minute after the LAST of them. The same
    transition wakes an unsettled seat at once. The damper is still a
    wall beside the settle, and the release clears both marks.
  - waymark-fp62.18.2 · the ADVANCE DOOR: a mirrored kind declares
    that one of its instants is an event, the driver opens that door
    when a pulled document moves the instant forward, and a seat
    wakes on the door and not on the etag. A birth opens nothing, a
    plain change opens nothing, an instant that moves backward opens
    nothing, and the entry's filter keeps another conversation's
    mention out of this seat.
  - docs/spec-walkthrough.md § 3 · the AUTHOR of a walkthrough is
    woken on its turn by one entry: the walkthrough's `start`, `step`,
    `resume`, `stop` and `finish`, under a filter that names the seat
    as `author` and `agent` as `waiting_on`. A start that opens an
    agent step fires the seat and names the walkthrough. A start that
    opens a person step wakes nobody, and the engine's `step` that
    ends it does. A wake an open sitting damped releases one textless
    fire, and the walkthrough is still the one row of the seat's
    `waiting_on=agent` queue.

  Each seat here links its OWN fire token, and the assertions count
  the fires carrying that token: the suite shares one fake provider
  and one database, and a seat left active by a neighbouring deftest
  is a seat this engine may legitimately wake.

  Real Postgres (WAYMARK10_TEST_DSN); no network — the fake scheduler
  and the fake fire endpoint stand at the provider."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.definitions :as defs]
            [waymark10.server.door-receivers :as doors]
            [waymark10.server.engine :as engine]
            [waymark10.server.invitations :as invitations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.schedules :as sch]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.wakes :as wakes]
            [waymark10.server.walkthroughs :as walkthroughs]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as factories]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the queue this house walks ──────────────────────────────────────

(def ^:private wake-task
  "One application kind, the smallest that can be walked: a queue that
  filters itself (what `walk-names-a-kind-in-scope` asks of a walk —
  one default filter, over any field of the kind), one door that ends
  a row and one that does not. The seat
  kinds are the REAL framework ones — all three are `:always` in the
  module table — so this is the suite's whole application."
  (r/resource
   {:kind :wake_task
    :plural "wake_tasks"
    :states [:open :complete]
    :initial :open
    :terminal #{:complete}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title {:examples ["Read the morning post"]
                      :x-display {:label "What it is"
                                  :help "One line naming the work."}}
              [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :default-filters {:state "open"}
    :actions
    {:complete {:from #{:open} :to :complete
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Done is done."}
                :display {:label "Complete" :style :primary :order 1}}
     :touch {:from #{:open} :to :open
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Touch" :order 2}}}}))

(def ^:private wake-item
  "The queue a COUNT wake counts (R-12.24). `wake_task` above cannot
  be it: a count is a number about a WHOLE collection, and the other
  deftests here leave their own rows in that queue — a neighbour's
  leftover row would move the number this suite asserts. So the count
  tests get a kind of their own, with a `batch` each deftest filters
  by, which is also what proves an entry's `filter` reaches the count
  at all."
  (r/resource
   {:kind :wake_item
    :plural "wake_items"
    :states [:open :complete]
    :initial :open
    :terminal #{:complete}
    :summary "{data.batch} · {state}"
    :schema [:map
             [:batch {:examples ["the morning post"]
                      :x-display {:label "Which batch"
                                  :help "The run of rows this one belongs to."}}
              [:string {:min 1 :max 40}]]]
    :filterable {:state #{:eq :in} :batch #{:eq :in}}
    :default-filters {:state "open"}
    :actions
    {:complete {:from #{:open} :to :complete
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Done is done."}
                :display {:label "Complete" :style :primary :order 1}}
     :touch {:from #{:open} :to :open
             :safety {:idempotent true :reversible true :confirm false}
             :display {:label "Touch" :order 2}}}}))

(def ^:private wake-memo
  "The queue a FILTERED walk walks (bead waymark-fp62.12). It
  declares NO default filter at all, so its collection opens on every
  row it has ever held — what narrows a seat's walk over it is the
  seat's own scope entry. Its two doors name each other: `send` takes
  a row out of the drafts and `recall` puts it back, which is what
  lets one test watch a row leave the filter and come back into it."
  (r/resource
   {:kind :wake_memo
    :plural "wake_memos"
    :states [:draft :sent]
    :initial :draft
    :terminal #{}
    :summary "{data.subject} · {state}"
    :schema [:map
             [:subject {:examples ["The gas bill"]
                        :x-display {:label "What it is about"
                                    :help "One line naming the memo."}}
              [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:send {:from #{:draft} :to :sent
            :safety {:idempotent true :reversible true :confirm false}
            :display {:label "Send" :style :primary :order 1}}
     :recall {:from #{:sent} :to :draft
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Recall" :order 2}}}}))

;; ── the conversations an ADVANCE DOOR speaks about ──────────────────
;;
;; waymark-fp62.18.2. A mirrored kind may declare that one of its
;; instants is an EVENT: the driver opens the declared door when a
;; pulled document carries a later instant than the stored row, beside
;; the observe the etag decides. This is the queue's `thread` kind in
;; miniature — one chat, when it last moved, and when the house was
;; last spoken to — because a seat that wants "the family mentioned
;; us" and not "somebody said something" can only ask for it when the
;; two are two doors.

(def ^:private chat-feed-state
  "The scriptable rig: {external-id document}."
  (atom {}))

(defrecord ChatFeed [state]
  mirror/MirrorAdapter
  (discover [_] (vec (sort (keys @state))))
  (pull [_ xid]
    (if-some [doc (get @state xid)]
      [doc (wire/digest doc)]
      (throw (ex-info (str xid " is not a chat this rig lists")
                      {:status 404}))))
  (pull-many [_ xids]
    (into {}
          (map (fn [xid]
                 [xid (if-some [doc (get @state xid)]
                        [doc (wire/digest doc)]
                        :gone)]))
          xids))
  (push [_ _ _]
    (throw (ex-info "the house does not write a conversation" {})))

  mirror/MirrorAdvanceAdapter
  ;; the rig can also say, cheaply, WHICH chats the house was named
  ;; in and when — the one question the advance beat asks
  ;; (waymark-fp62.18.3)
  (advance-listing [_]
    (into {}
          (keep (fn [[xid doc]]
                  (when-some [m (:last_mention_at doc)]
                    [xid {:last_mention_at m}])))
          @state)))

(def ^:private chat-feed (->ChatFeed chat-feed-state))

(def ^:private wake-chat
  (r/resource
   (mirror/declaration
    {:kind :wake_chat
     :plural "wake_chats"
     :summary "{data.title}"
     :schema
     [:map
      [:title {:optional true
               :examples ["Meal plans"]
               :x-display {:label "What the conversation is called"
                           :help "The name the rig shows for this chat."}}
       [:maybe [:string {:max 80}]]]
      [:last_message_at {:optional true
                         :x-display
                         {:label "When something was last said"
                          :help "The rig's own time for the last message in this chat."}}
       [:maybe :waymark/instant]]
      [:last_mention_at {:optional true
                         :x-display
                         {:label "When the house was last spoken to"
                          :help "The time of the last message that named the house."}}
       [:maybe :waymark/instant]]]}
    {:adapter chat-feed
     :ttl-seconds 300
     :discover-every 300
     :advances {:observe_mention
                {:field :last_mention_at
                 :label "Observed a mention of the house"
                 :help "The rig heard a message that named the house."}}
     ;; and the door has a beat of its own: the seat must hear the
     ;; house named in seconds, not at the top of the hour
     :advance-every 20})))

;; ── the row a walkthrough's person step points at ───────────────────

(def ^:private wake-form
  "The row a PERSON step of a walkthrough names
  (docs/spec-walkthrough.md § 2). A person step points at arguments of
  a door, and no other kind in this suite has a door that takes any."
  (r/resource
   {:kind :wake_form
    :plural "wake_forms"
    :states [:open :filed]
    :initial :open
    :terminal #{:filed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title {:examples ["The gas bill"]
                      :x-display {:label "What it is called"
                                  :help "One line naming the form."}}
              [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :default-filters {:state "open"}
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map
                      [:title {:examples ["The water bill"]
                               :x-display {:label "What it is called"
                                           :help "One line naming the form."}}
                       [:string {:min 1 :max 80}]]]
              :handler (fn [row inp _ctx]
                         (update row :data merge (select-keys inp [:title])))
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Rename" :order 1}}
     :file {:from #{:open} :to :filed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "Filed is filed."}
            :display {:label "File" :style :primary :order 2}}}}))

(def ^:private wake-pr
  "A row a forge mirrors (ticket 70aca5e6): `observe` is the poll's
  self-loop on a red pull request, and it writes the head it read."
  (r/resource
   {:kind :wake_pr
    :plural "wake_prs"
    :states [:failing :closed]
    :initial :failing
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title {:examples ["Fix the gate"]
                      :x-display {:label "What it is"
                                  :help "One line naming the change."}}
              [:string {:min 1 :max 80}]]
             [:head_sha {:optional true
                         :examples ["0123abcd"]
                         :x-display {:label "Head"
                                     :help "The commit the forge last read."}}
              [:maybe [:string {:max 64}]]]]
    :filterable {:state #{:eq :in}}
    :default-filters {:state "failing"}
    :actions
    {:observe {:from #{:failing} :to :failing
               :input [:map
                       [:head_sha {:examples ["0123abcd"]
                                   :x-display {:label "Head"
                                               :help "The commit the forge read."}}
                        [:string {:min 1 :max 64}]]]
               :handler (fn [row inp _ctx]
                          (update row :data merge (select-keys inp [:head_sha])))
               :safety {:idempotent true :reversible true :confirm false}
               :display {:label "Observe" :order 1}}
     :close {:from #{:failing} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Closed is closed."}
             :display {:label "Close" :style :primary :order 2}}}}))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["wake_tasks" "wake_items" "wake_memos" "wake_chats" "wake_forms" "wake_prs"
   "schedules" "seats" "models" "sittings" "definitions"
   "members" "roles" "grants" "approval_requests" "attachments"
   "subscriptions" "jobs" "invitations" "walkthroughs"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_cursors"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *fire* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [fire (sch/fake-fire)
              eng (engine/engine
                   {:storage st
                    :resources [wake-task wake-item wake-memo wake-chat
                                wake-form wake-pr]})]
          (binding [*eng* (assoc eng :fire-adapter fire)
                    *fire* fire]
            (f)))
        (finally (pg/close! st))))))

;; ── the hands ───────────────────────────────────────────────────────

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))
(def ^:private clerk (t/principal {:id "clerk" :type :agent :display "Clerk"}))

;; ── writers ─────────────────────────────────────────────────────────

(def ^:private a-scope [{:kind "wake_task" :actions ["complete"]}])
(def ^:private a-charter
  "Decide whether a row in this queue asks something of this house.")

(defn- seat-body [nm extra]
  (merge {:name nm
          :charter a-charter
          :scope a-scope
          :held_for []
          :substitute_for []
          :standing_ttl_seconds 604800
          :cadence_seconds 3600
          :budget_usd_per_week 5M
          :sitting_budget_tokens 60000
          :rows_per_firing 20}
         extra))

(defn- restate-body [extra]
  (merge {:charter a-charter
          :scope a-scope
          :held_for []
          :substitute_for []
          :standing_ttl_seconds 604800
          :cadence_seconds 3600
          :budget_usd_per_week 5M
          :sitting_budget_tokens 60000
          :rows_per_firing 20}
         extra))

(defn- seat! [nm extra]
  (:id (:row (inv/create! *eng* :seat (seat-body nm extra)
                          {:principal elena}))))

(defn- raw [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx] (store/load-row (:storage *eng*) tx kind (str id) {}))))

(defn- wake-entries-of
  "The `wake_on`-shaped entries a seat's managed subscriptions carry on
  its fire door (E-5.4): kind, actions, and the receiver's size and
  settle. Read from the subscriptions, not from the seat row."
  [seat-id]
  (let [st (:storage *eng*)]
    (into []
          (comp (filter #(and (= (str seat-id) (str (get-in % [:data :receiver :id])))
                              (contains? #{"seat" "owner"}
                                         (some-> (get-in % [:data :managed_by]) name))))
                (map (fn [sub]
                       (merge {:kind (first (get-in sub [:data :kinds]))
                               :actions (vec (get-in sub [:data :actions]))}
                              (select-keys (get-in sub [:data :receiver])
                                           [:at_least :at_most :settle_seconds])))))
          (store/with-tx st
            #(store/query-rows st % :subscription {:state :active} {:limit 500})))))

(defn- restate! [seat-id extra]
  (let [row (raw :seat seat-id)]
    (inv/invoke! *eng* :seat (str seat-id) :restate (restate-body extra)
                 {:principal elena
                  :if-match (inv/etag :seat (str seat-id) (:version row))})))

(defn- seat-do! [seat-id action]
  ;; a test's seat is often a state's only staffer: its retire opens
  ;; that crack on purpose (ticket a77bf705)
  (inv/invoke! *eng* :seat (str seat-id) action nil
               {:principal elena :acknowledged #{:opens-no-crack}}))

(defn- task! [title]
  (:id (:row (inv/create! *eng* :wake_task {:title title}
                          {:principal elena}))))

(defn- task-do! [id action]
  (inv/invoke! *eng* :wake_task (str id) action nil {:principal elena}))

(def ^:private count-scope
  "A counting seat's leash: the queue it walks and the queue it
  counts. A count entry naming a kind outside the scope would still
  wake the seat — the declaration says so — but a seat that is woken
  by a number it may not read is not the case under test."
  [{:kind "wake_task" :actions ["complete"]}
   {:kind "wake_item" :actions ["complete"]}])

(defn- item! [batch]
  (:id (:row (inv/create! *eng* :wake_item {:batch batch}
                          {:principal elena}))))

(defn- item-do! [id action]
  (inv/invoke! *eng* :wake_item (str id) action nil {:principal elena}))


(defn- memo! [subject]
  (:id (:row (inv/create! *eng* :wake_memo {:subject subject}
                          {:principal elena}))))

(defn- memo-do! [id action]
  (inv/invoke! *eng* :wake_memo (str id) action nil {:principal elena}))


(defn- model! [nm]
  (:id (:row (inv/create! *eng* :model
                          {:name nm :display nm :vendor "anthropic"
                           :tier "strong"
                           :price_input_per_mtok 3M
                           :price_output_per_mtok 15M
                           :price_cache_read_per_mtok 0.3M
                           :price_cache_write_per_mtok 3.75M}
                          {:principal elena}))))

(defn- grant! []
  (:id (:row (inv/create! *eng* :grant
                          {:audience "clerk" :scope a-scope}
                          {:principal elena}))))

(defn- sitting! [seat-id]
  (:id (:row (inv/create! *eng* :sitting
                          {:seat (str seat-id)
                           :model (str (model! (str "model-for-" seat-id)))
                           :grant (str (grant!))}
                          {:principal clerk}))))

(defn- close-sitting! [sitting-id]
  (inv/invoke! *eng* :sitting (str sitting-id) :close
               {:input_tokens 1000 :output_tokens 100
                :cache_read_tokens 0 :cache_write_tokens 0 :turns 1}
               {:principal clerk}))

;; ── readers ─────────────────────────────────────────────────────────

(defn- sched-of [seat-id] (sch/schedule-for-seat *eng* seat-id))

(defn- log-of [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (store/transitions (:storage *eng*) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- fires-of
  "The POSTs that went to THIS seat's Routine, in order. The fake
  provider is shared by every deftest in this namespace, so a count
  of all its fires would be a count of the suite."
  [token]
  (filterv #(= token (:token %)) (sch/fires *fire*)))

(defn- seat-fires
  "The seat's own `fire` transitions."
  [seat-id]
  (filterv #(= :fire (:action %)) (log-of :seat seat-id)))

(defn- fire-text
  "The text of the seat's nth fire (0-based), read as the JSON object
  it is — the payload block the provider puts into the session."
  [seat-id n]
  (some-> (get-in (nth (seat-fires seat-id) n) [:inputs :text])
          str
          wire/read-json))

(defn- refusal
  "The problem ex-data of a write that was refused, or nil when it was
  served."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

;; ── the two drains ──────────────────────────────────────────────────
;;
;; Each under its OWN named cursor, seeded before the writes it is
;; about — schedules_test's discipline, for its reason: clojure.test
;; promises no order between deftests, and a from-origin drain in one
;; of them would replay another's transitions.

(defn- drain-wakes! [cname]
  (consumers/drain-consumer! *eng* cname (wakes/consumer-fn *eng*)))

(defn- drain-fires! [cname]
  (consumers/drain-consumer! *eng* cname (sch/consumer-fn *eng*)))

(defn- linked-seat!
  "A seat, its schedule minted and pushed, and a person's link on it
  carrying a token of this seat's own. → {:seat id :token token}."
  [nm extra fire-cursor]
  (let [seat-id (seat! nm extra)
        token (str "rk-test-" nm "-0123456789abcdef")]
    (drain-fires! fire-cursor)
    (inv/invoke! *eng* :schedule (str (:id (sched-of seat-id))) :link
                 {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                 "/routines/trig_" nm "/fire")
                  :token token}
                 {:principal elena})
    {:seat seat-id :token token}))

;; ── the door subscription ───────────────────────────────────────────
;;
;; A seat here is woken by a subscription on its fire door
;; (docs/spec-events.md §4.2), written from a `wake_on`-shaped entry.
;; Its match fires nothing: it leaves the wake pending, and the sweep
;; fires it (E-3.5). So a test that drained the wakes after a match
;; drains the doors and sweeps instead.
;;
;; Left on `wake_on`, for 4c93bba0 to decide:
;; - the tests whose subject is `wake_on` itself: its write-door
;;   refusals, the E-5.2 empty-actions entries, the walk's computed
;;   default, and the `:wakes` consumer's own replay and its close and
;;   missed-fire re-judging of count wakes;
;; - the tests that assert a TEXTLESS release (the sitting close, the
;;   tick after the gap, the settle, the empty queue, the grace, the
;;   fuel wall, the pool, the slots, the damped walkthrough): a door's
;;   pending wake keeps the text of the row it heard, so its release
;;   names that row; and the throttle, whose release after the wait
;;   sends one fire for each row that waited, not one;
;; - the fuel wall's `halted` stamp and the unsent fire's
;;   `wake_fired_at`, which only the immediate fire writes;
;; - every walk seat: one with no `wake_on` is woken by its walk's
;;   computed default, at once, beside any subscription;
;; - the walkthrough author, whose `leading-seat!` the damped
;;   walkthrough test shares.

(defn- subscribe!
  "A door subscription on the seat's fire door from one `wake_on`-shaped
  entry, written by Elena."
  [seat-id entry]
  (factories/door-subscription! *eng* {:principal elena} seat-id entry))

(defn- door-seat!
  "`linked-seat!`, woken by a door subscription for each entry instead
  of a `wake_on`. → {:seat id :token token}."
  [nm extra entries fire-cursor]
  (let [linked (linked-seat! nm extra fire-cursor)]
    (doseq [e entries] (subscribe! (:seat linked) e))
    linked))

(defn- drain-doors!
  "The door drain marks a match pending, and the sweep fires it."
  []
  (doors/drain! *eng*)
  (wakes/sweep-pending! *eng*))

;; ── 1 · case 36: the match wakes the seat, once ─────────────────────

(deftest a-matching-transition-wakes-the-seat-and-names-the-row
  (let [wn :wake-match
        fn' :wake-match-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (door-seat! "wakeclerk" {}
                    [{:kind "wake_task" :actions ["complete"]}]
                    fn')
        row-id (task! "the first thing")]
    (task-do! row-id :complete)
    (drain-doors!)

    (testing "the seat fired once, and the text names the row that moved"
      (let [ts (seat-fires seat)]
        (is (= 1 (count ts)))
        (let [text (str (get-in (first ts) [:inputs :text]))]
          (is (str/includes? text (str row-id)))
          (is (str/includes? text "wake_task"))
          (is (str/includes? text "complete"))
          (is (str/includes? text "open")
              "the from state is in the text too — the session is told
               what moved, not merely that something did"))))

    (testing "and exactly one POST reached this seat's Routine"
      (drain-fires! fn')
      (is (= 1 (count (fires-of token))))
      (is (str/includes? (str (:text (last (fires-of token)))) (str row-id)))
      (is (some? (get-in (sched-of seat) [:data :last_fired_at]))))

    (testing "a second match inside the gap does not fire — it waits"
      (let [second-id (task! "the second thing")]
        (task-do! second-id :complete)
        (drain-doors!)
        (is (= 1 (count (seat-fires seat)))
            "no second fire transition")
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))
            "and the match is remembered on the schedule row")
        (drain-fires! fn')
        (is (= 1 (count (fires-of token))))))

    (testing "a transition of another action, and one of another kind,
              wake nothing at all"
      (task-do! (task! "not a completion") :touch)
      (inv/invoke! *eng* :model (str (model! "wake-unwanted-model")) :retire
                   nil {:principal elena})
      (drain-doors!)
      (drain-fires! fn')
      (is (= 1 (count (fires-of token)))))

    ;; leave no active seat behind: a seat still asking to be woken is
    ;; one the next deftest's own rows would legitimately wake
    (seat-do! seat :retire)))

;; ── 2 · case 37: the open sitting holds the wake ────────────────────

(deftest a-match-during-a-sitting-waits-for-that-sitting-to-close
  (let [wn :wake-sitting
        fn' :wake-sitting-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (linked-seat! "sittingclerk"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]}
                      fn')
        open-one (sitting! seat)
        row-id (task! "while somebody is already awake")]
    (task-do! row-id :complete)
    (drain-wakes! wn)

    (testing "case 37: the open sitting stops the fire"
      (is (empty? (seat-fires seat)))
      (drain-fires! fn')
      (is (empty? (fires-of token))))

    (testing "and the match is not lost — it waits on the schedule row"
      (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

    (testing "the close releases exactly one fire, and it names NO row"
      (close-sitting! open-one)
      (drain-wakes! wn)
      (let [ts (seat-fires seat)]
        (is (= 1 (count ts)))
        (is (nil? (get-in (first ts) [:inputs :text]))
            "a textless fire walks the queue, as a cadence wake does"))
      (drain-fires! fn')
      (is (= 1 (count (fires-of token))))
      (is (nil? (:text (last (fires-of token))))))

    (testing "and the flag is cleared, so nothing releases it twice"
      (is (not (get-in (sched-of seat) [:data :wake_pending])))
      (wakes/sweep-pending! *eng*)
      (drain-fires! fn')
      (is (= 1 (count (fires-of token)))))

    (seat-do! seat :retire)))

;; ── 3 · the tick releases what the gap held ─────────────────────────

(deftest the-tick-releases-a-wake-the-gap-held
  (let [wn :wake-tick
        fn' :wake-tick-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (linked-seat! "tickclerk"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]
                       :fire_interval_seconds 1}
                      fn')]
    (task-do! (task! "the first of two") :complete)
    (drain-wakes! wn)
    (drain-fires! fn')
    (is (= 1 (count (fires-of token))) "the first match fires at once")

    (task-do! (task! "the second, inside the gap") :complete)
    (drain-wakes! wn)
    (is (= 1 (count (fires-of token))))
    (is (true? (get-in (sched-of seat) [:data :wake_pending])))

    (testing "once the gap has passed, the sweep fires the waiting wake"
      ;; the gap is a DURATION, and nothing commits when a duration
      ;; ends — which is the whole reason the module starts a tick
      (Thread/sleep 1200)
      (wakes/sweep-pending! *eng*)
      (let [ts (seat-fires seat)]
        (is (= 2 (count ts)))
        (is (nil? (get-in (last ts) [:inputs :text]))))
      (drain-fires! fn')
      (is (= 2 (count (fires-of token))))
      (is (nil? (:text (last (fires-of token)))))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (seat-do! seat :retire)))

(deftest a-wake-that-waits-says-why-on-the-schedule-row
  ;; ticket d1c371db: a heard wake that starts no run leaves its reason
  (let [wn :wake-silent
        fn' :wake-silent-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (door-seat! "silentclerk" {:fire_interval_seconds 3}
                    [{:kind "wake_task" :actions ["complete"]}]
                    fn')]
    (task-do! (task! "the first of two, said") :complete)
    (drain-doors!)
    (drain-fires! fn')
    (is (= 1 (count (fires-of token))) "the first match fires at once")
    (is (nil? (get-in (sched-of seat) [:data :last_silent_wake])))

    (task-do! (task! "the second, held by the gap") :complete)
    (doors/drain! *eng*)
    (wakes/sweep-pending! *eng*)
    (testing "the wake the gap holds names the gap, and when it began"
      (is (= 1 (count (seat-fires seat))))
      (is (true? (get-in (sched-of seat) [:data :wake_pending])))
      (is (= "fire_gap" (get-in (sched-of seat) [:data :last_silent_wake])))
      (is (some? (get-in (sched-of seat) [:data :last_silent_wake_at]))))

    (testing "the fire that goes out takes the reason off"
      (Thread/sleep 3200)
      (wakes/sweep-pending! *eng*)
      (is (= 2 (count (seat-fires seat))))
      (is (nil? (get-in (sched-of seat) [:data :last_silent_wake])))
      (is (nil? (get-in (sched-of seat) [:data :last_silent_wake_at]))))

    (seat-do! seat :retire)))

(deftest a-damped-match-delivered-twice-fires-the-seat-once
  ;; waymark-fp62.21: the drain re-delivered a damped match while the
  ;; released run's sitting was open, and the next release fired again
  (let [wn :wake-replay-damped
        fn' :wake-replay-damped-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (linked-seat! "damperclerk"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]
                       :fire_interval_seconds 1}
                      fn')
        open-one (sitting! seat)
        task (task! "one mention")]
    (task-do! task :complete)
    (drain-wakes! wn)
    (is (empty? (seat-fires seat)) "the open sitting damps the match")
    (is (true? (get-in (sched-of seat) [:data :wake_pending])))

    (close-sitting! open-one)
    (drain-wakes! wn)
    (is (= 1 (count (seat-fires seat))) "the close releases the wake")

    (testing "the same transition delivered again while the released run sits"
      ;; `sitting!` registers one model per seat; the second sitting
      ;; names a model of its own
      (let [open-two (:id (:row (inv/create! *eng* :sitting
                                             {:seat (str seat)
                                              :model (str (model! (str "model-two-for-" seat)))
                                              :grant (str (grant!))}
                                             {:principal clerk})))
            t (last (filter #(= :complete (:action %)) (log-of :wake_task task)))]
        (is (some? t))
        (wakes/handle-transition! *eng* (atom nil) t)
        (is (not (get-in (sched-of seat) [:data :wake_pending]))
            "a replay sets nothing pending")
        (Thread/sleep 1200)
        (close-sitting! open-two)
        (drain-wakes! wn)
        (wakes/sweep-pending! *eng*)
        (is (= 1 (count (seat-fires seat))) "one match, one fire")))

    (seat-do! seat :retire)))

(deftest a-throttled-routine-stays-live-and-its-wake-goes-out-after-the-time-it-named
  (let [wn :wake-throttle
        fn' :wake-throttle-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (linked-seat! "throttleclerk"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]
                       :fire_interval_seconds 1}
                      fn')]
    (try
      (sch/answer! *fire* 429 {:retry-after "2"})
      (task-do! (task! "the one the provider throttles") :complete)
      (drain-wakes! wn)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of token))) "the throttled POST went out once")
      (let [row (sched-of seat)]
        (is (= :live (:state row)) "a throttle is not a broken link")
        (is (true? (get-in row [:data :wake_pending])))
        (is (some? (get-in row [:data :retry_after])))
        (is (= "The Routine has no free run. Try again after 2."
               (get-in row [:data :note]))))

      (testing "before the time it named nothing goes out, and a new match folds in"
        (task-do! (task! "a second match, inside the throttle") :complete)
        (drain-wakes! wn)
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 1 (count (seat-fires seat))))
        (is (= 1 (count (fires-of token))))
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

      (testing "after it, the sweep sends the waiting wake exactly once"
        (Thread/sleep 2200)
        (wakes/sweep-pending! *eng*)
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 2 (count (seat-fires seat))))
        (is (= 2 (count (fires-of token))))
        (let [row (sched-of seat)]
          (is (= :live (:state row)))
          (is (nil? (get-in row [:data :retry_after])))
          (is (nil? (get-in row [:data :note])))
          (is (not (get-in row [:data :wake_pending])))))
      (finally
        (sch/answer! *fire* nil)
        (seat-do! seat :retire)))))

;; ── 4 · the walk seat's computed default ────────────────────────────

(deftest a-walk-seat-with-no-wake-on-wakes-on-its-own-queue
  (let [wn :wake-walk
        fn' :wake-walk-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (linked-seat! "walkclerk" {:walk "wake_task"} fn')]

    (testing "the engine writes nothing and computes the one entry"
      (let [row (raw :seat seat)]
        (is (= [{:kind "wake_task" :actions ["create"]}]
               (wake-entries-of seat))
            "R-12.22: the default lives on its managed subscription")
        (is (= [{:kind "wake_task" :actions ["create"]}]
               (seats/effective-wake-on row)))))

    (testing "and a row arriving in the queue wakes the seat"
      (let [row-id (task! "a row for the walk")]
        (drain-wakes! wn)
        (drain-fires! fn')
        (is (= 1 (count (fires-of token))))
        (is (str/includes? (str (:text (last (fires-of token))))
                           (str row-id)))))

    (testing "a seat with neither a walk nor a wake_on wakes on nothing"
      (let [quiet (seat! "quietclerk" {})]
        (is (= [] (seats/effective-wake-on (raw :seat quiet))))
        (seat-do! quiet :retire)))

    (seat-do! seat :retire)))

;; ── 4b · the FILTERED walk's computed default (R-4 of fp62.12) ──────
;;
;; A walk seat that wrote no `wake_on` wakes on `create` of its queue.
;; When the walk's scope entry carries a filter, the queue is that
;; filter — and a row arrives in it two ways, because somebody can
;; also MOVE a row into it. So the computed entry names every action
;; of the kind, under the same filter, and the consumer judges the row
;; after the transition committed.

(deftest a-filtered-walk-wakes-on-a-door-that-moves-a-row-into-it
  (let [wn :wake-filtered
        fn' :wake-filtered-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        ;; written and sent BEFORE the seat exists: both transitions
        ;; are in the log the drain will read, and the row is OUTSIDE
        ;; the filter at the moment the drain judges it
        memo-id (memo! "a memo for the filtered walk")
        _ (memo-do! memo-id :send)
        {:keys [seat token]}
        (linked-seat! "memoclerk"
                      {:walk "wake_memo"
                       :scope [{:kind "wake_memo" :actions ["send"]
                                :filter {:state "draft"}}]}
                      fn')]

    (testing "the engine writes nothing and computes the filtered entry"
      (let [row (raw :seat seat)
            computed (seats/effective-wake-on row wake-memo)
            entry (first computed)]
        (is (= [{:kind "wake_memo" :actions []}] (wake-entries-of seat))
            "R-12.22: its managed subscription names every action, here as anywhere")
        (is (= 1 (count computed)))
        (is (= "wake_memo" (str (:kind entry))))
        (is (= ["create" "recall" "send"] (mapv str (:actions entry)))
            "every action of the kind, and the birth door with them")
        (is (= {"state" "draft"}
               (into {} (map (fn [[k v]] [(name k) (str v)])) (:filter entry)))
            "under the walk's own scope entry filter")
        (is (nil? (:at_least entry))
            "and it is a transition wake, as the create default always was")))

    (testing "a row that sits outside the filter wakes nothing"
      (drain-wakes! wn)
      (drain-fires! fn')
      (is (= 0 (count (fires-of token)))
          "its create and its send are both in the log, and the row is sent"))

    (testing "and the door that moves it back INTO the filter fires once"
      (memo-do! memo-id :recall)
      (drain-wakes! wn)
      (is (= 1 (count (seat-fires seat))))
      (drain-fires! fn')
      (is (= 1 (count (fires-of token))))
      (let [text (str (:text (last (fires-of token))))]
        (is (str/includes? text (str memo-id)))
        (is (str/includes? text "wake_memo"))
        (is (str/includes? text "recall")
            "the text names the door that moved it, so the session walks it")))

    (seat-do! seat :retire)))

;; ── 5 · the replay wakes once ───────────────────────────────────────

(deftest a-replayed-match-wakes-the-seat-once
  (let [wn :wake-replay
        twin :wake-replay-twin
        fn' :wake-replay-fires
        ;; all three cursors seed HERE, before this test's writes
        _ (drain-wakes! wn)
        _ (drain-wakes! twin)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (linked-seat! "replayclerk"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]}
                      fn')
        row-id (task! "exactly once")]
    (task-do! row-id :complete)
    (drain-wakes! wn)
    (is (= 1 (count (seat-fires seat))))

    (testing "a second drain over the same transitions opens no second
              fire — the key is the transition it heard, so invoke
              answers the stored result"
      ;; the twin drains BEFORE the fire goes out, so it is the KEY
      ;; that stops the second wake here and not the gap
      (is (nil? (get-in (sched-of seat) [:data :last_fired_at])))
      (drain-wakes! twin)
      (is (= 1 (count (seat-fires seat)))))

    (testing "and one POST reaches the Routine"
      (drain-fires! fn')
      (is (= 1 (count (fires-of token)))))

    (seat-do! seat :retire)))

;; ── 6 · the declaration refuses a wake nobody can match ─────────────

(deftest a-wake-on-entry-that-names-no-action-is-refused-at-both-doors
  (testing "at create, with the entry named"
    (let [p (refusal #(inv/create!
                       *eng* :seat
                       (seat-body "wakeliar"
                                  {:wake_on [{:kind "wake_task"
                                              :actions ["explode"]}]})
                       {:principal elena}))]
      (is (= :wake-on-names-real-actions (:guard p)))
      (is (str/includes? (str (:detail p)) "explode")
          "the refusal spells the entry that failed, not 'invalid wake_on'")
      (is (str/includes? (str (:detail p)) "wake_task"))))

  (testing "and a restate takes no wake_on at all (E-5.4): what wakes
            the seat is restated on its fire door's subscriptions"
    (let [seat (seat! "wakehonest"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]})
          p (refusal #(restate! seat {:wake_on [{:kind "nosuchkind"
                                                 :actions ["complete"]}]}))]
      (is (= :schema-invalid (:waymark10/problem p)))
      (is (str/includes? (pr-str (:errors p)) "wake_on"))
      (is (= [{:kind "wake_task" :actions ["complete"]}]
             (wake-entries-of seat))
          "and its subscriptions did not move")
      (seat-do! seat :retire)))

  (testing "and an entry naming an engine-own kind is refused with the
            kind to wake on instead"
    (let [p (refusal #(inv/create! *eng* :seat
                                   (seat-body "wakeownkind"
                                              {:wake_on [{:kind "sitting"
                                                          :actions ["close"]}]})
                                   {:principal elena}))]
      (is (= :wake-on-names-no-engine-kind (:guard p)))
      (is (str/includes? (str (:detail p)) "sitting"))
      (is (str/includes? (str (:detail p)) "transcript seal"))))

  (testing "a gap of zero seconds is refused by the schema"
    (let [p (refusal #(inv/create! *eng* :seat
                                   (seat-body "zerogap"
                                              {:fire_interval_seconds 0})
                                   {:principal elena}))]
      (is (= :schema-invalid (:waymark10/problem p)))
      (is (str/includes? (pr-str (:errors p)) "fire_interval_seconds"))))

  (testing "and a seat that names no gap is born with the default"
    (let [seat (seat! "defaultgap" {})]
      (is (= 300 (get-in (raw :seat seat) [:data :fire_interval_seconds])))
      (seat-do! seat :retire))))

;; ── 7 · R-12.24: the count wake waits for the size ──────────────────

(deftest a-count-wake-fires-when-the-queue-reaches-its-size
  (let [wn :wake-count
        fn' :wake-count-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        batch "count-of-twenty"
        {:keys [seat token]}
        (door-seat! "countclerk" {:scope count-scope}
                    [{:kind "wake_item"
                      :actions ["create"]
                      :filter {:batch batch}
                      :at_least 20}]
                    fn')]

    (testing "nineteen rows wake nobody"
      (dotimes [_ 19] (item! batch))
      (drain-doors!)
      (is (empty? (seat-fires seat)))
      (drain-fires! fn')
      (is (empty? (fires-of token)))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))
          "and nothing waits on the schedule row: a count below the
           size is not a damped match, it is no match at all"))

    (testing "the twentieth fires once, and the text carries the count"
      (item! batch)
      (drain-doors!)
      (is (= 1 (count (seat-fires seat))))
      (let [text (fire-text seat 0)]
        (is (= "wake_item" (:kind text)))
        (is (= 20 (:count text)))
        (is (= 20 (:at_least text)))
        (is (nil? (:id text))
            "no row id: a count wake names a queue, so the session
             walks it rather than one row")))

    (testing "and exactly one POST reached this seat's Routine"
      (drain-fires! fn')
      (is (= 1 (count (fires-of token))))
      (is (str/includes? (str (:text (last (fires-of token)))) "\"count\"")))

    (seat-do! seat :retire)))

;; ── 8 · the damper holds a count wake too ───────────────────────────

(deftest a-damped-count-wake-releases-one-fire-when-the-sitting-closes
  (let [wn :wake-count-damped
        fn' :wake-count-damped-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        batch "count-damped"
        {:keys [seat token]}
        (linked-seat! "countdampclerk"
                      {:scope count-scope
                       :wake_on [{:kind "wake_item"
                                  :actions ["create"]
                                  :filter {:batch batch}
                                  :at_least 2}]}
                      fn')
        open-one (sitting! seat)]
    (item! batch)
    (item! batch)
    (drain-wakes! wn)

    (testing "the open sitting holds the fire, and the match waits"
      (is (empty? (seat-fires seat)))
      (drain-fires! fn')
      (is (empty? (fires-of token)))
      (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

    (testing "the close releases exactly one fire, and it names no row"
      (close-sitting! open-one)
      (drain-wakes! wn)
      (let [ts (seat-fires seat)]
        (is (= 1 (count ts)))
        (is (nil? (get-in (first ts) [:inputs :text]))
            "the release walks the whole queue, count wake or not"))
      (drain-fires! fn')
      (is (= 1 (count (fires-of token))))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (seat-do! seat :retire)))

;; ── 8a · the close judges the count wakes again ─────────────────────
;;
;; Ticket 668a845d. A seat that takes one row per sitting writes a
;; verdict, not the row it counted, so nothing of the counted kind
;; follows its close. The close itself is the evaluation point.

(defn- nth-sitting!
  "`sitting!`, with a model of its own for each run of one seat."
  [seat-id n]
  (:id (:row (inv/create! *eng* :sitting
                          {:seat (str seat-id)
                           :model (str (model! (str "model-for-" seat-id "-" n)))
                           :grant (str (grant!))}
                          {:principal clerk}))))

(defn- counting-seat!
  "A linked seat counting one batch's open items as they are created,
  its first run already released by the close of a sitting that was
  open while two items arrived. → {:seat :token :ids}."
  [nm batch interval wn fn' entry-extra]
  (let [linked (linked-seat! nm
                             {:scope count-scope
                              :wake_on [(merge {:kind "wake_item"
                                                :actions ["create"]
                                                :filter {:batch batch}}
                                               entry-extra)]
                              :fire_interval_seconds interval}
                             fn')
        first-run (nth-sitting! (:seat linked) 1)
        ids (vec (repeatedly 2 #(item! batch)))]
    (drain-wakes! wn)
    (close-sitting! first-run)
    (drain-wakes! wn)
    (assoc linked :ids ids)))

(deftest a-count-wake-is-judged-again-when-a-sitting-closes
  (let [wn :wake-count-close
        fn' :wake-count-close-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token ids]}
        (counting-seat! "countcloseclerk" "count-close" 1 wn fn'
                        {:at_least 1})]

    (testing "the two creates waited on the open sitting, and its close
              released them as one fire"
      (is (= 1 (count (seat-fires seat))))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (testing "a run that takes one row and closes is fired again, since
              one row is still waiting"
      (let [run (nth-sitting! seat 2)]
        (item-do! (first ids) :complete)
        (drain-wakes! wn)
        (is (not (get-in (sched-of seat) [:data :wake_pending]))
            "a completion is not an action the entry counts")
        (Thread/sleep 1200)
        (close-sitting! run)
        (drain-wakes! wn)
        (let [ts (seat-fires seat)]
          (is (= 2 (count ts)))
          (is (nil? (get-in (last ts) [:inputs :text]))
              "the fire names no row"))
        (drain-fires! fn')
        (is (= 2 (count (fires-of token))))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))))

    (testing "a close with the queue empty fires nothing"
      (let [run (nth-sitting! seat 3)]
        (item-do! (second ids) :complete)
        (drain-wakes! wn)
        (Thread/sleep 1200)
        (close-sitting! run)
        (drain-wakes! wn)
        (is (= 2 (count (seat-fires seat))))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))))

    (seat-do! seat :retire)))

(deftest a-count-wake-is-judged-again-when-a-sitting-expires
  ;; leases 3b: an expiry is heard as a close is, so a seat whose
  ;; sitting's lease ran out is woken exactly as one whose sitting closed
  (let [wn :wake-count-expire
        fn' :wake-count-expire-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat ids]}
        (counting-seat! "countexpireclerk" "count-expire" 1 wn fn'
                        {:at_least 1})
        run (nth-sitting! seat 2)]
    (is (= 1 (count (seat-fires seat))))
    (item-do! (first ids) :complete)
    (drain-wakes! wn)
    (Thread/sleep 1200)
    ;; the sweep's own end of it: the lease set past, then `expire`
    (let [st (:storage *eng*)]
      (store/with-tx st
        (fn [tx]
          (store/update-data! st tx :sitting (str run)
                              (assoc (:data (store/load-row st tx :sitting (str run) {}))
                                     :expires_at "2000-01-01T00:00:00Z")
                              nil))))
    (inv/invoke! *eng* :sitting (str run) :expire nil {:principal seats/seats-actor})
    (drain-wakes! wn)
    (is (= 2 (count (seat-fires seat)))
        "the expiry released the wake the row still waiting holds")
    (is (not (get-in (sched-of seat) [:data :wake_pending])))
    (seat-do! seat :retire)))

(deftest a-close-inside-the-gap-holds-the-count-wake-until-it-lifts
  (let [wn :wake-count-close-gap
        fn' :wake-count-close-gap-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat ids]}
        (counting-seat! "countgapclerk" "count-close-gap" 2 wn fn'
                        {:at_least 1})
        run (nth-sitting! seat 2)]
    (item-do! (first ids) :complete)
    (drain-wakes! wn)
    (close-sitting! run)
    (drain-wakes! wn)

    (testing "the close inside fire_interval_seconds fires nothing, and
              the wake waits on the schedule row"
      (is (= 1 (count (seat-fires seat))))
      (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

    (testing "once the gap has passed, the sweep fires it"
      (Thread/sleep 2200)
      (wakes/sweep-pending! *eng*)
      (let [ts (seat-fires seat)]
        (is (= 2 (count ts)))
        (is (nil? (get-in (last ts) [:inputs :text]))))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (seat-do! seat :retire)))

(deftest a-close-replays-no-transition-wake
  (let [wn :wake-transition-close
        fn' :wake-transition-close-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (counting-seat! "transitioncloseclerk" "transition-close" 1 wn fn'
                        {})
        run (nth-sitting! seat 2)]
    (is (= 1 (count (seat-fires seat)))
        "the match the open sitting held is released, as it always was")

    (testing "a seat with no count wake is not fired by a later close,
              though rows of the kind it watches are still open"
      (Thread/sleep 1200)
      (close-sitting! run)
      (drain-wakes! wn)
      (is (= 1 (count (seat-fires seat))))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (seat-do! seat :retire)))

;; ── 9 · the entry's filter decides what is counted ──────────────────

(deftest a-count-wake-counts-only-the-rows-its-filter-names
  (let [wn :wake-count-filter
        fn' :wake-count-filter-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        batch "count-filtered"
        ;; the kind's DEFAULT filter is state=open; this entry names
        ;; state=complete, so the two count different rows of the same
        ;; batch and the number in the text says which one ran
        {:keys [seat token]}
        (door-seat! "countfilterclerk" {:scope count-scope}
                    [{:kind "wake_item"
                      :actions ["complete"]
                      :filter {:batch batch :state "complete"}
                      :at_least 2}]
                    fn')
        ids (vec (repeatedly 3 #(item! batch)))]

    (testing "three rows waiting is not two rows complete"
      (drain-doors!)
      (is (empty? (seat-fires seat))))

    (testing "the first completion counts one, which is not enough"
      (item-do! (first ids) :complete)
      (drain-doors!)
      (is (empty? (seat-fires seat)))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (testing "the second reaches the size, and the count is the
              filter's two — not the batch's three, and not the one
              row the kind's own default filter would have left open"
      (item-do! (second ids) :complete)
      (drain-doors!)
      (is (= 1 (count (seat-fires seat))))
      (let [text (fire-text seat 0)]
        (is (= "wake_item" (:kind text)))
        (is (= 2 (:count text)))
        (is (= 2 (:at_least text))))
      (drain-fires! fn')
      (is (= 1 (count (fires-of token)))))

    (seat-do! seat :retire)))

;; ── 10 · the transition wake's filter names the rows that wake it ───

(deftest a-transition-wake-fires-only-for-the-rows-its-filter-names
  (let [wn :wake-transition-filter
        fn' :wake-transition-filter-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        mine "transition-filter-mine"
        theirs "transition-filter-theirs"
        ;; the same entry twice, once with a filter and once without,
        ;; over the same two rows: the only difference between these
        ;; two seats is the filter, so the fires say what it did
        picky (door-seat! "pickyclerk" {:scope count-scope}
                          [{:kind "wake_item"
                            :actions ["complete"]
                            :filter {:batch mine}}]
                          fn')
        anyone (door-seat! "anyoneclerk"
                           {:scope count-scope
                            :fire_interval_seconds 1}
                           [{:kind "wake_item"
                             :actions ["complete"]}]
                           fn')
        theirs-id (item! theirs)
        mine-id (item! mine)]

    (testing "another batch's row completes, and the filtered seat is
              not woken — nothing waits on its schedule row either,
              because this is no match at all rather than a match the
              damper held"
      (item-do! theirs-id :complete)
      (drain-doors!)
      (drain-fires! fn')
      (is (empty? (seat-fires (:seat picky))))
      (is (empty? (fires-of (:token picky))))
      (is (not (get-in (sched-of (:seat picky)) [:data :wake_pending]))))

    (testing "the same entry with no filter wakes on that very row, as
              it always did"
      (is (= 1 (count (seat-fires (:seat anyone)))))
      (is (= (str theirs-id) (:id (fire-text (:seat anyone) 0))))
      (is (= 1 (count (fires-of (:token anyone))))))

    ;; the gap is the unfiltered seat's own damper, not the filter's
    ;; doing: let it pass, so the second completion is judged by the
    ;; filter alone (case 3's sleep, for case 3's reason)
    (Thread/sleep 1200)

    (testing "the filter's own batch completes, the seat fires once,
              and the text names the row that moved — the kind's
              default filter (state=open) is not imposed on a row this
              very transition completed"
      (item-do! mine-id :complete)
      (drain-doors!)
      (drain-fires! fn')
      (is (= 1 (count (seat-fires (:seat picky)))))
      (let [text (fire-text (:seat picky) 0)]
        (is (= "wake_item" (:kind text)))
        (is (= (str mine-id) (:id text)))
        (is (= "complete" (:action text))))
      (is (= 1 (count (fires-of (:token picky))))))

    (testing "and the unfiltered entry wakes on both rows"
      (is (= 2 (count (seat-fires (:seat anyone)))))
      (is (= (str mine-id) (:id (fire-text (:seat anyone) 1))))
      (is (= 2 (count (fires-of (:token anyone))))))

    (seat-do! (:seat picky) :retire)
    (seat-do! (:seat anyone) :retire)))

;; ── 10b · a comma value is any of on a field that declares :in ──────

(deftest a-comma-filter-on-an-in-field-reads-as-any-of
  (let [a "anyof-a" b "anyof-b" c "anyof-c"
        a-id (item! a) b-id (item! b) c-id (item! c)
        f {:batch (str a "," b)}]
    (testing "a transition wake's filter judges a row of either batch in,
              and a third batch's row out"
      (is (true? (wakes/moved-under? *eng* :wake_item a-id f)))
      (is (true? (wakes/moved-under? *eng* :wake_item b-id f)))
      (is (false? (wakes/moved-under? *eng* :wake_item c-id f))))
    (testing "a count wake with that filter counts both batches"
      (is (= 2 (wakes/count-under *eng* :wake_item f))))))

(deftest a-comma-wake-filter-on-a-field-without-in-is-refused-at-create
  (let [p (refusal #(inv/create! *eng* :seat
                                 (seat-body "wakecomma"
                                            {:wake_on [{:kind "wake_task"
                                                        :actions ["complete"]
                                                        :filter {:title "a,b"}}]})
                                 {:principal elena}))]
    (is (= :wake-on-any-of-needs-in (:guard p)))
    (is (str/includes? (str (:detail p)) "title")
        "the refusal names the field, not 'invalid wake_on'")))

;; ── 11 · a count entry with no actions counts on every action ───────

(deftest an-empty-actions-list-is-every-action-on-both-entries
  (let [transition {:kind "wake_item" :actions ["create"]}
        count-all {:kind "wake_item" :actions [] :at_least 5}
        transition-all {:kind "wake_item" :actions []}]
    (testing "a count entry naming no action counts on every action of
              its kind (R-12.24's default)"
      (is (wakes/matches? count-all :wake_item :touch))
      (is (wakes/matches? count-all :wake_item :create))
      (is (not (wakes/matches? count-all :wake_task :create))
          "its kind, and no other"))
    (testing "a transition entry naming none wakes on every action of
              its kind too (E-5.2)"
      (is (wakes/matches? transition-all :wake_item :create))
      (is (wakes/matches? transition-all :wake_item :touch))
      (is (not (wakes/matches? transition-all :wake_task :create))
          "its kind, and no other"))
    (testing "and the entries a transition matches come back in the
              order the seat wrote them"
      (is (= [transition transition-all count-all]
             (wakes/matching-entries [transition transition-all count-all]
                                     :wake_item :create)))
      (is (= [transition-all count-all]
             (wakes/matching-entries [transition transition-all count-all]
                                     :wake_item :complete))))))

(deftest an-entry-naming-no-action-wakes-on-any-action-of-its-kind
  (let [kept {:kind "wake_task" :actions ["complete"]}
        born (seat! "emptywakeafter" {:wake_on [kept {:kind "wake_task" :actions []}]})
        written (get-in (raw :seat born) [:data :wake_on])]
    (is (= [kept {:kind "wake_task" :actions []}] written))
    (is (wakes/matches? (second written) :wake_task :create))
    (is (wakes/matches? (second written) :wake_task :complete))
    (seat-do! born :retire)))

;; ── 12 · a size below one row is refused at the create door ─────────

(deftest a-count-wake-of-fewer-than-one-row-is-refused

  (testing "at_least 0 is the schema's own refusal, and it names the
            field that failed"
    (let [p (refusal #(inv/create!
                       *eng* :seat
                       (seat-body "countzero"
                                  {:wake_on [{:kind "wake_task"
                                              :actions ["create"]
                                              :at_least 0}]})
                       {:principal elena}))]
      (is (= :schema-invalid (:waymark10/problem p)))
      (is (str/includes? (pr-str (:errors p)) "at_least"))
      (is (str/includes? (pr-str (:errors p)) "wake_on"))))

  (testing "and a seat that asks for one row is born with it"
    (let [seat (seat! "countone"
                      {:wake_on [{:kind "wake_task"
                                  :actions ["create"]
                                  :at_least 1}]})]
      (is (= [{:kind "wake_task" :actions ["create"] :at_least 1}]
             (wake-entries-of seat)))
      (seat-do! seat :retire))))

;; ── 13 · a wake's fire carries the composed text ────────────────────
;;
;; Bead waymark-fp62.7.23, R-3: the wake writes the PROSE — the row
;; that moved, as JSON — and the schedules consumer wraps the seat's
;; own instructions around it. Both fires are one rule (R-5), so what
;; a person's fire carries is what a wake's carries.

(def ^:private the-instructions
  "Read the fire text and do what it says. Sit in the seat it names, then walk the rows the sit hands you.")

(deftest a-wake-fires-the-seats-instructions-around-the-row-it-names
  (let [wn :wake-instructed
        fn' :wake-instructed-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (door-seat! "instructedclerk" {:instructions the-instructions}
                    [{:kind "wake_task" :actions ["complete"]}]
                    fn')
        row-id (task! "the thing that woke it")]
    (task-do! row-id :complete)
    (drain-doors!)
    (drain-fires! fn')

    (testing "the POST carries the seat's instructions first"
      (let [text (str (:text (last (fires-of token))))]
        (is (str/starts-with? text the-instructions))
        (is (str/includes? text (str "Seat: " seat " (instructedclerk).")))
        (is (str/includes? text "<routine-fire-payload>"))
        (is (str/includes? text (str row-id))
            "and the wake's own prose, whole, inside the block")
        (is (str/includes? text "</routine-fire-payload>"))))

    (testing "and the transition log still carries the prose alone"
      (let [logged (fire-text seat 0)]
        (is (= (str row-id) (str (:id logged))))
        (is (= "wake_task" (:kind logged)))
        (is (not (str/includes? (str (get-in (first (seat-fires seat))
                                             [:inputs :text]))
                                the-instructions))
            "what a person restates is composed after the door closed")))

    ;; ── R-12.37 · and the key of THIS firing, on the line under the
    ;; seat's. The Routine's prompt holds no secret, so there is no
    ;; step for a person to miss.
    (let [text (str (:text (last (fires-of token))))
          key (second (re-find #"(?m)^Key: (\S+)$" text))
          held (get-in (raw :seat seat) [:data :fire_keys])]

      (testing "the fire text carries a key, under the line that names
                the seat"
        (is (string? key) "one line, and the engine minted it")
        (is (<= 22 (count (str key))) "128 bits, base64url")
        (is (str/includes? text (str "Seat: " seat " (instructedclerk).\nKey: "
                                     key))
            "the two the sit needs, side by side"))

      (testing "the seat row keeps the HASH and never the key"
        (is (= 1 (count held)))
        (is (= (seats/key-hash key) (str (:hash (first held)))))
        (is (not (str/includes? (pr-str held) (str key)))
            "nothing on the row could be presented at the door"))

      (testing "the transition of the fire carries no key"
        (is (not (str/includes? (str (get-in (first (seat-fires seat))
                                             [:inputs :text]))
                                (str key)))
            "the composed text is the wire's, and the record is the
             person's prose"))

      (testing "the key opens this seat one time, and then answers nothing"
        (is (= (str seat)
               (str (:id (seats/seat-for-key *eng* "instructedclerk" key)))))
        (is (true? (seats/spend-fire-key! *eng* (raw :seat seat) key)))
        (is (nil? (seats/seat-for-key *eng* "instructedclerk" key))
            "a second sit with the same key reads the uniform sentence")))

    (seat-do! seat :retire)))

;; ── 14 · R-12.24: the count wake that waits for an empty queue ──────
;;
;; Bead waymark-fp62.13. `at_least` counts UP and nothing could wake a
;; seat on absence: the planner whose work begins when no plan is
;; waiting had its cadence and nothing else. `at_most` counts DOWN,
;; and `at_most` 0 is the empty queue.

(deftest an-at-most-wake-fires-when-the-last-row-leaves-the-filter
  (let [wn :wake-at-most
        fn' :wake-at-most-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        batch "count-to-empty"
        {:keys [seat token]}
        (door-seat! "emptyclerk" {:scope count-scope}
                    [{:kind "wake_item"
                      :actions ["complete"]
                      :filter {:batch batch}
                      :at_most 0}]
                    fn')
        ids (vec (repeatedly 2 #(item! batch)))]

    (testing "two rows arrive, and `create` is not one of this entry's
              actions, so nothing is counted and nothing fires"
      (drain-doors!)
      (is (empty? (seat-fires seat)))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (testing "the first completion leaves one row waiting, which is
              not an empty queue"
      (item-do! (first ids) :complete)
      (drain-doors!)
      (is (empty? (seat-fires seat)))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))
          "and nothing waits on the schedule row: a queue above the
           size is no match at all, not a match the damper held")
      (drain-fires! fn')
      (is (empty? (fires-of token))))

    (testing "the second empties the filter, and the seat fires once"
      (item-do! (second ids) :complete)
      (drain-doors!)
      (is (= 1 (count (seat-fires seat))))
      (let [text (fire-text seat 0)]
        (is (= "wake_item" (:kind text)))
        (is (= 0 (:count text)))
        (is (= 0 (:at_most text))
            "the size it was asked in, in the entry's own word")
        (is (nil? (:at_least text))
            "and not the size it was not asked in")
        (is (nil? (:id text))
            "no row id: the session walks the queue, and the charter
             says what to make when the queue is empty")))

    (testing "and exactly one POST reached this seat's Routine"
      (drain-fires! fn')
      (is (= 1 (count (fires-of token))))
      (is (str/includes? (str (:text (last (fires-of token)))) "at_most")))

    (seat-do! seat :retire)))

;; ── 15 · an entry names one size, never two ─────────────────────────

(deftest an-entry-that-names-both-sizes-is-refused

  (testing "at_least and at_most in one entry is the schema's own
            refusal, and it lands in the entry's own place"
    (let [p (refusal #(inv/create!
                       *eng* :seat
                       (seat-body "countboth"
                                  {:wake_on [{:kind "wake_task"
                                              :actions ["create"]
                                              :at_least 5
                                              :at_most 0}]})
                       {:principal elena}))]
      (is (= :schema-invalid (:waymark10/problem p)))
      (is (str/includes? (pr-str (:errors p)) "wake_on"))
      (is (str/includes? (pr-str (:errors p))
                         "An entry names at_least or at_most, not both.")
          "the sentence says which two fields quarrelled, not 'invalid'")))

  (testing "a size below zero is refused as at_least's below one is"
    (let [p (refusal #(inv/create!
                       *eng* :seat
                       (seat-body "countbelowzero"
                                  {:wake_on [{:kind "wake_task"
                                              :actions ["create"]
                                              :at_most -1}]})
                       {:principal elena}))]
      (is (= :schema-invalid (:waymark10/problem p)))
      (is (str/includes? (pr-str (:errors p)) "at_most"))
      (is (str/includes? (pr-str (:errors p)) "wake_on"))))

  (testing "and a seat woken by an empty queue is born with its entry"
    (let [seat (seat! "countempty"
                      {:wake_on [{:kind "wake_task"
                                  :actions ["complete"]
                                  :at_most 0}]})]
      (is (= [{:kind "wake_task" :actions ["complete"] :at_most 0}]
             (wake-entries-of seat)))
      (seat-do! seat :retire))))

;; ── 16 · the count text says which way the seat was counting ────────

(deftest the-count-text-carries-the-size-the-entry-asked-in
  (testing "an at_least entry's text is the one R-12.24 spells"
    (let [text (wire/read-json (wakes/count-text :wake_item 23 :at_least 20))]
      (is (= {:kind "wake_item" :count 23 :at_least 20} text))))
  (testing "and an at_most entry's carries at_most in its place"
    (let [text (wire/read-json (wakes/count-text :wake_item 0 :at_most 0))]
      (is (= {:kind "wake_item" :count 0 :at_most 0} text))))
  (testing "either way the text names no row, so the session walks
            the queue rather than one row"
    (is (nil? (:id (wire/read-json (wakes/count-text :wake_item 0 :at_most 0)))))))

;; ── 17 · an at_most entry is a count entry ──────────────────────────

(deftest an-at-most-entry-is-a-count-entry
  (let [at-most {:kind "wake_item" :actions [] :at_most 0}
        at-least {:kind "wake_item" :actions [] :at_least 5}
        transition {:kind "wake_item" :actions ["complete"]}]
    (is (wakes/count-entry? at-most))
    (is (wakes/count-entry? at-least))
    (is (not (wakes/count-entry? transition)))
    (testing "so an at_most entry with no actions counts on every
              action of its kind, as an at_least entry does"
      (is (wakes/matches? at-most :wake_item :complete))
      (is (wakes/matches? at-most :wake_item :create))
      (is (not (wakes/matches? at-most :wake_task :complete))))))

;; ── 18 · the wake that SETTLES (bead waymark-fp62.17) ──────────────
;;
;; Every entry above fires on the LEADING edge: the first match wakes
;; the seat. An entry that names `settle_seconds` fires on the
;; trailing edge instead, and this is the case the family chat asks
;; for. The first reply is the middle of the conversation. The seat
;; must read the chat when the replies stop.
;;
;; The settle is a duration of a minute, and this suite waits for no
;; minute: the engine's clock is an atom for the length of this
;; deftest, so the sweep can be asked before the quiet time ends and
;; again after it. The tick's body is that same `sweep-pending!`.

(defn- due-of
  "The moment the seat's waiting wake is due, as an Instant, or nil
  when nothing is due."
  [seat-id]
  (some-> (get-in (sched-of seat-id) [:data :wake_due_at]) str Instant/parse))

(deftest a-settled-wake-fires-after-the-matches-stop-and-not-before
  (let [wn :wake-settle
        fn' :wake-settle-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat token]}
            (linked-seat! "settleclerk"
                          {:fire_interval_seconds 120
                           :wake_on [{:kind "wake_task"
                                      :actions ["complete"]
                                      :settle_seconds 60}]}
                          fn')
            plain (linked-seat! "nosettleclerk"
                                {:wake_on [{:kind "wake_task"
                                            :actions ["complete"]}]}
                                fn')]

        (testing "the first match fires nothing, and the wake is due
                  one minute after it"
          (task-do! (task! "the first word of a conversation") :complete)
          (drain-wakes! wn)
          (is (empty? (seat-fires seat)))
          (drain-fires! fn')
          (is (empty? (fires-of token)))
          (is (true? (get-in (sched-of seat) [:data :wake_pending]))
              "the match is remembered, as a damped match is")
          (is (= (.plusSeconds t0 60) (due-of seat))))

        (testing "and the very same transition wakes an UNSETTLED seat
                  at once. The settle is the only difference between
                  these two seats"
          (is (= 1 (count (seat-fires (:seat plain)))))
          (is (= 1 (count (fires-of (:token plain)))))
          (is (nil? (due-of (:seat plain)))
              "nothing is due on a seat that fires on the match")
          (seat-do! (:seat plain) :retire))

        (testing "a second match inside the window moves the due
                  moment forward"
          (at 20)
          (task-do! (task! "the second word") :complete)
          (drain-wakes! wn)
          (is (empty? (seat-fires seat)))
          (is (= (.plusSeconds t0 80) (due-of seat))))

        (testing "and a third moves it forward again"
          (at 40)
          (task-do! (task! "the third word") :complete)
          (drain-wakes! wn)
          (is (empty? (seat-fires seat)))
          (is (= (.plusSeconds t0 100) (due-of seat))))

        (testing "the sweep holds the wake while the window is open,
                  one minute after the FIRST match and past it"
          (at 40)
          (wakes/sweep-pending! *eng*)
          (is (empty? (seat-fires seat)))
          (at 99)
          (wakes/sweep-pending! *eng*)
          (is (empty? (seat-fires seat))
              "99 seconds is long past the first match's own minute")
          (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

        (testing "one minute after the LAST match the sweep fires once,
                  and the fire names no row"
          (at 100)
          (wakes/sweep-pending! *eng*)
          (let [ts (seat-fires seat)]
            (is (= 1 (count ts)))
            (is (nil? (get-in (first ts) [:inputs :text]))
                "a textless fire walks the queue, so the session reads
                 the whole conversation and not its first word"))
          (drain-fires! fn')
          (is (= 1 (count (fires-of token))))
          (is (nil? (:text (last (fires-of token))))))

        (testing "and the release clears both marks together"
          (is (not (get-in (sched-of seat) [:data :wake_pending])))
          (is (nil? (due-of seat)))
          (wakes/sweep-pending! *eng*)
          (is (= 1 (count (seat-fires seat)))
              "a second sweep releases nothing: there is nothing left"))

        (testing "the damper is still a wall of its own: a settled
                  wake whose window has closed waits for the gap"
          (at 110)
          (task-do! (task! "a word of the next conversation") :complete)
          (drain-wakes! wn)
          (is (= (.plusSeconds t0 170) (due-of seat)))
          (at 175)
          (wakes/sweep-pending! *eng*)
          (is (= 1 (count (seat-fires seat)))
              "the settle has passed, and the seat fired 75 seconds
               ago inside its own gap of 120")
          (is (true? (get-in (sched-of seat) [:data :wake_pending]))
              "so the wake is still waiting, and it is not lost"))

        (testing "and once the gap has passed too, the second wake
                  goes out"
          (at 225)
          (wakes/sweep-pending! *eng*)
          (let [ts (seat-fires seat)]
            (is (= 2 (count ts)))
            (is (nil? (get-in (last ts) [:inputs :text]))))
          (drain-fires! fn')
          (is (= 2 (count (fires-of token))))
          (is (not (get-in (sched-of seat) [:data :wake_pending])))
          (is (nil? (due-of seat))))

        (seat-do! seat :retire)))))

;; A settle that every match restarts never ends on a busy engine
;; (ticket 8f482592): matches every 300 seconds against a settle of
;; 600 moved the due moment forward for ever, and the seat did not sit
;; for days. The first match's instant is the cap's anchor, and the
;; seat's cadence is the backstop for an entry whose cap is longer.

(deftest a-settled-wake-is-not-held-back-for-ever-by-matches-that-never-stop
  (let [wn :wake-settle-cap
        fn' :wake-settle-cap-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [word! (fn [secs]
                    (at secs)
                    (task-do! (task! (str "a word at " secs)) :complete)
                    (drain-wakes! wn))
            capped (:seat (linked-seat! "cappedclerk"
                                        {:fire_interval_seconds 120
                                         :wake_on [{:kind "wake_task"
                                                    :actions ["complete"]
                                                    :settle_seconds 600}]}
                                        fn'))
            backed (:seat (linked-seat! "backedclerk"
                                        {:fire_interval_seconds 120
                                         :cadence_seconds 3600
                                         :wake_on [{:kind "wake_task"
                                                    :actions ["complete"]
                                                    :settle_seconds 600
                                                    :max_wait_seconds 86400}]}
                                        fn'))]

        (testing "matches every 300 seconds move the due moment forward
                  only as far as three settles after the FIRST one"
          (doseq [secs [0 300 600 900 1200 1500]]
            (word! secs))
          (is (empty? (seat-fires capped)))
          (is (= (.plusSeconds t0 1800) (due-of capped))
              "the match at 1500 asked for 2100, and the cap is 1800")
          (is (= (.plusSeconds t0 2100) (due-of backed))
              "an entry that names a longer max_wait_seconds is moved on"))

        (testing "the wake goes out at the cap, and not before it"
          (at 1799)
          (wakes/sweep-pending! *eng*)
          (is (empty? (seat-fires capped)))
          (at 1800)
          (wakes/sweep-pending! *eng*)
          (is (= 1 (count (seat-fires capped))))
          (is (nil? (due-of capped)))
          (is (nil? (get-in (sched-of capped) [:data :wake_first_at]))
              "the fire clears the first match with the other marks")
          (is (empty? (seat-fires backed)))
          (seat-do! capped :retire))

        (testing "the cadence is the backstop: a whole cadence after the
                  first match the wake goes out, whatever is due"
          (doseq [secs [1800 2100 2400 2700 3000 3300]]
            (word! secs))
          (is (= (.plusSeconds t0 3900) (due-of backed)))
          (at 3599)
          (wakes/sweep-pending! *eng*)
          (is (empty? (seat-fires backed)))
          (at 3600)
          (wakes/sweep-pending! *eng*)
          (is (= 1 (count (seat-fires backed))))
          (is (nil? (due-of backed))))

        (seat-do! backed :retire)))))

;; ── 19 · the wake an ADVANCE DOOR opens (bead waymark-fp62.18.2) ────
;;
;; The last wake in this file, and the narrowest. Every change to a
;; mirrored row moves its etag and lands `observe_external`, so a seat
;; woken by that door is woken by every word in every conversation. A
;; kind that declares an advance door gets a second sentence — "the
;; instant I named moved forward" — and the seat asks for THAT one.
;;
;; The seat here is the meal-planner's shape from
;; docs/routines/meal-planner.md: one chat named by its external id,
;; the mention door, and a settle, because the family is still talking
;; when the first message arrives.

(defn- chat-row
  "The mirrored row for one external id."
  [xid]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (first (store/query-rows (:storage *eng*) tx :wake_chat
                               {:external_id xid} {:limit 1})))))

(defn- mentions-of
  "The `observe_mention` transitions on one chat row."
  [xid]
  (filterv #(= :observe_mention (:action %))
           (log-of :wake_chat (:id (chat-row xid)))))

(deftest a-seat-wakes-on-a-mention-and-not-on-a-plain-message
  (let [wn :wake-mention
        fn' :wake-mention-fires
        chat "tgram:-5091757250"
        other "tgram:-4400000001"
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))]
    (reset! chat-feed-state
            {chat {:title "Meal plans"
                   :last_message_at "2026-09-20T17:00:00Z"
                   :last_mention_at "2026-09-20T16:30:00Z"}
             other {:title "Bros."
                    :last_message_at "2026-09-20T17:00:00Z"
                    :last_mention_at "2026-09-20T16:30:00Z"}})
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (drain-wakes! wn)
      (drain-fires! fn')
      (mirror/discover! *eng* :wake_chat)

      (testing "a BIRTH opens no advance door: the rig has held that
                mention for half an hour, and a row minted with it did
                not move this minute"
        (is (some? (chat-row chat)))
        (is (empty? (mentions-of chat))))

      (let [{:keys [seat token]}
            (door-seat! "mentionclerk" {}
                        [{:kind "wake_chat"
                          :actions ["observe_mention"]
                          :filter {:external_id chat}
                          :settle_seconds 300}]
                        fn')]

        (testing "a plain message moves the row and wakes nobody: the
                  etag changed, the mention did not"
          (swap! chat-feed-state assoc-in [chat :last_message_at]
                 "2026-09-20T18:00:00Z")
          (is (= 1 (:rewritten (mirror/resync! *eng* :wake_chat))))
          (doors/drain! *eng*)
          (is (empty? (mentions-of chat)))
          (is (empty? (seat-fires seat)))
          (is (nil? (due-of seat))))

        (testing "a mention lands ONE transition, beside the observe,
                  and the row carries the new time"
          (swap! chat-feed-state assoc-in [chat :last_mention_at]
                 "2026-09-20T18:05:00Z")
          (mirror/resync! *eng* :wake_chat)
          (is (= 1 (count (mentions-of chat))))
          ;; the row is read raw off the store, where the instant is
          ;; its wire spelling; a decoded row holds an Instant, and
          ;; `str` of either is the same text
          (is (= "2026-09-20T18:05:00Z"
                 (str (get-in (chat-row chat) [:data :last_mention_at])))))

        (testing "and the seat wakes on it — on the trailing edge, so
                  the session reads a conversation and not its first
                  word"
          (doors/drain! *eng*)
          (is (empty? (seat-fires seat)))
          (is (= (.plusSeconds t0 300) (due-of seat)))
          (at 301)
          (wakes/sweep-pending! *eng*)
          (is (= 1 (count (seat-fires seat))))
          (drain-fires! fn')
          (is (= 1 (count (fires-of token)))))

        (testing "a mention in ANOTHER conversation is not this seat's:
                  the entry's filter names one chat, and every chat in
                  the house would be one sitting an hour"
          (swap! chat-feed-state assoc-in [other :last_mention_at]
                 "2026-09-20T19:00:00Z")
          (mirror/resync! *eng* :wake_chat)
          (is (= 1 (count (mentions-of other)))
              "the door opened on the row that moved")
          (doors/drain! *eng*)
          (at 700)
          (wakes/sweep-pending! *eng*)
          (is (= 1 (count (seat-fires seat)))
              "and no second fire reached the seat")
          (is (nil? (due-of seat))))

        (testing "a mention that moves BACKWARD opens nothing: a rig
                  that re-reads its own history is not the family
                  speaking again"
          (swap! chat-feed-state assoc-in [chat :last_mention_at]
                 "2026-09-20T17:05:00Z")
          (mirror/resync! *eng* :wake_chat)
          (is (= 1 (count (mentions-of chat))))
          (doors/drain! *eng*)
          (at 1100)
          (wakes/sweep-pending! *eng*)
          (is (= 1 (count (seat-fires seat)))))

        (seat-do! seat :retire)))))

;; ── and the beat is what makes that wake quick ──────────────────────
;;
;; waymark-fp62.18.3. The door above opens when the mirror LOOKS, and
;; the looking was the whole-kind heal (an hour) or a read past the
;; TTL. The seat is the house answering a person, so the kind declares
;; :advance-every 20: the daemon asks the rig which mentions moved and
;; refreshes those rows alone.

(deftest the-beat-wakes-the-seat-in-seconds-and-not-at-the-hour
  (let [wn :wake-beat
        fn' :wake-beat-fires
        chat "tgram:-5091757260"
        quiet "tgram:-4400000002"
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))]
    (reset! chat-feed-state
            {chat {:title "Meal plans"
                   :last_message_at "2026-09-21T17:00:00Z"
                   :last_mention_at "2026-09-21T16:30:00Z"}
             quiet {:title "Bros."
                    :last_message_at "2026-09-21T17:00:00Z"
                    :last_mention_at "2026-09-21T16:30:00Z"}})
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (drain-wakes! wn)
      (drain-fires! fn')
      (mirror/discover! *eng* :wake_chat)
      (let [{:keys [seat token]}
            (door-seat! "beatclerk" {}
                        [{:kind "wake_chat"
                          :actions ["observe_mention"]
                          :filter {:external_id chat}
                          :settle_seconds 300}]
                        fn')
            rdef (get (inv/resources *eng*) :wake_chat)
            untouched (count (log-of :wake_chat (:id (chat-row quiet))))]

        (swap! chat-feed-state assoc-in [chat :last_mention_at]
               "2026-09-21T18:26:00Z")

        (testing "the pull-through alone would not have seen it: the
                  row is fresh inside its TTL, so a read serves the
                  stored truth and the mention waits for the hour"
          (mirror/refresh! *eng* rdef
                           (inv/decode-row rdef (chat-row chat)))
          (is (= "2026-09-21T16:30:00Z"
                 (str (get-in (chat-row chat) [:data :last_mention_at]))))
          (is (empty? (mentions-of chat))))

        (testing "one beat asks the rig which mentions moved and
                  refreshes THAT row: the instant lands, and the door
                  opens once beside the observe"
          (is (= {:listed 2 :moved 1} (mirror/advance-beat! *eng* :wake_chat)))
          (is (= "2026-09-21T18:26:00Z"
                 (str (get-in (chat-row chat) [:data :last_mention_at]))))
          (is (= 1 (count (mentions-of chat)))))

        (testing "the chat nobody named is not touched at all — a beat
                  costs the rows that moved and no others"
          (is (= untouched (count (log-of :wake_chat (:id (chat-row quiet)))))))

        (testing "and the seat wakes on it, on the settle's trailing
                  edge: seconds after the family spoke, not at the top
                  of the hour"
          (doors/drain! *eng*)
          (is (= (.plusSeconds t0 300) (due-of seat)))
          (at 301)
          (wakes/sweep-pending! *eng*)
          (is (= 1 (count (seat-fires seat))))
          (drain-fires! fn')
          (is (= 1 (count (fires-of token)))))

        (testing "a second beat over the same listing opens nothing:
                  the door is a MOVE and not a level"
          (is (= {:listed 2 :moved 0} (mirror/advance-beat! *eng* :wake_chat)))
          (is (= 1 (count (mentions-of chat)))))

        (seat-do! seat :retire)))))

;; ── a fire nobody sat in ──────────────────────────────────────────────
;;
;; A fire opens its sitting (ticket 9100c7b9), so a fire nobody sits on
;; ends THAT sitting at the sit deadline through `expire`, by the clock
;; sweep's lease pass — missed, never sat, and the wake armed again
;; once. No second sitting is born closed beside it: never-sat is only
;; a lease expiry.

(deftest a-fire-nobody-sat-in-ends-its-own-sitting-through-expire
  (let [fn' :wake-fire-sittings
        _ (drain-fires! fn')
        batch "fire-sitting"
        ^Instant t0 (.truncatedTo (Instant/now) java.time.temporal.ChronoUnit/SECONDS)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat]}
            (linked-seat! "firesitclerk"
                          {:scope count-scope
                           :instructions "Read the fire text and do what it says."
                           ;; a fire opens a sitting only for a seat that names a model
                           :held_for [(str (model! "model-for-firesitclerk"))]
                           :wake_on [{:kind "wake_item"
                                      :actions ["create"]
                                      :filter {:batch batch}
                                      :at_least 2}]}
                          fn')
            seat-row #(raw :seat seat)
            ;; a fire opens a sitting only once the seat's sitter exists
            fire! (fn []
                    (let [s (seats/open-fire-sitting! *eng* (seat-row) @clock)]
                      (seats/hold-fire-key! *eng* (seat-row) @clock nil (:id s))
                      s))
            entry-of (fn [s]
                       (some #(when (= (str (:id s)) (str (:sitting %))) %)
                             (get-in (seat-row) [:data :fire_keys])))
            ;; the clock sweep, whose lease pass expires the sitting
            sweep! #(defs/sweep-clock! *eng*)
            missed #(store/with-tx (:storage *eng*)
                      (fn [tx]
                        (store/query-rows (:storage *eng*) tx :sitting
                                          {:seat (str seat) :missed true}
                                          {:limit 50})))
            clear-pending! #(let [s (sched-of seat)]
                              (store/with-tx (:storage *eng*)
                                (fn [tx]
                                  (store/update-data! (:storage *eng*) tx :schedule
                                                      (str (:id s))
                                                      (dissoc (:data s) :wake_pending)
                                                      (:next-flip-at s)))))]
        (item! batch)
        (item! batch)
        ((requiring-resolve 'waymark10.server.members/ensure-sitter!)
         *eng* (seats/sitter-id (seat-row)) (seats/sitter-display (seat-row))
         (str (:id elena)))

        (testing "the fire opens one sitting, leased to the sit deadline, and
                  its key names it; nobody sits, and expire ends it as missed"
          (let [^Instant fired (at 1000)
                s (fire!)]
            (is (some? s) "the seat names a model and its sitter row exists")
            (is (= (.plusSeconds fired 600)
                   (Instant/parse (str (get-in (raw :sitting (:id s))
                                               [:data :expires_at])))))
            (is (some? (entry-of s)))
            (at 1300)
            (sweep!)
            (is (= :open (:state (raw :sitting (:id s))))
                "five minutes on, the run may still be starting")
            (is (empty? (missed)))
            (at 1601)
            (sweep!)
            (is (= [(str (:id s))] (mapv #(str (:id %)) (missed)))
                "one never_sat sitting for the fire, and none born closed beside it")
            (let [row (raw :sitting (:id s))
                  d (:data row)]
              (is (= :closed (:state row)))
              (is (= "expiry" (some-> (:closed_by d) name)))
              (is (true? (:missed d)))
              (is (= "never_sat" (some-> (:outcome d) name)))
              (is (str/includes? (str (:note d)) "no session sat within 600s.")))
            (is (nil? (entry-of s)) "the entry is gone off the seat row")
            (is (true? (get-in (sched-of seat) [:data :wake_pending]))
                "the count wake still holds, so the queue fires again")))

        (testing "a second missed fire in a row is not armed again"
          (clear-pending!)
          (at 2000)
          (let [s (fire!)]
            (at 2601)
            (sweep!)
            (is (true? (get-in (raw :sitting (:id s)) [:data :missed])))
            (is (not (get-in (sched-of seat) [:data :wake_pending])))))

        (testing "a not-started fire's sitting carries its note and is not armed again"
          (clear-pending!)
          (at 3000)
          (let [s (fire!)]
            (is (true? (wakes/record-not-started!
                        *eng* {:seat (str seat) :hash (:hash (entry-of s))}
                        "the laptop slept")))
            (is (= :open (:state (raw :sitting (:id s))))
                "the expire ends it, at the deadline")
            (at 3601)
            (sweep!)
            (is (= (wakes/not-started-note "the laptop slept")
                   (str (get-in (raw :sitting (:id s)) [:data :note]))))
            (is (not (get-in (sched-of seat) [:data :wake_pending])))))

        (seat-do! seat :retire)))))

;; ── the fire nobody carried out ─────────────────────────────────────
;;
;; Production, 2026-10-04 and 2026-10-05: code-seat's wake stamped
;; `wake_fired_at`, `last_fired_at` stayed where it was, no run sat and
;; no missed sitting was written. The key the missed sweep reads is
;; minted where the fire is carried out, so a fire nobody carried out
;; had nothing to miss (ticket cc2a7754).

(deftest a-wake-fire-nobody-carried-out-leaves-a-missed-sitting-and-its-wake-comes-back
  (let [wn :wake-unsent
        fn' :wake-unsent-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat token]}
            (linked-seat! "unsentclerk"
                          {:instructions "Read the fire text and do what it says."
                           :wake_on [{:kind "wake_task" :actions ["complete"]}]}
                          fn')
            missed #(store/with-tx (:storage *eng*)
                      (fn [tx]
                        (store/query-rows (:storage *eng*) tx :sitting
                                          {:seat (str seat) :missed true}
                                          {:limit 50})))
            row-id (task! "the thing nobody ran")]
        (task-do! row-id :complete)
        (drain-wakes! wn)

        (testing "the wake stamped its own clock, and the fire was not
                  carried out: no stamp of a run, no key, no POST"
          (is (= 1 (count (seat-fires seat))))
          (is (some? (get-in (sched-of seat) [:data :wake_fired_at])))
          (is (nil? (get-in (sched-of seat) [:data :last_fired_at])))
          (is (empty? (get-in (raw :seat seat) [:data :fire_keys])))
          (is (empty? (fires-of token)))
          (is (not (get-in (sched-of seat) [:data :wake_pending]))))

        (testing "five minutes on, the fire may still be on its way"
          (at 300)
          (wakes/sweep-unsent! *eng*)
          (is (empty? (missed))))

        (testing "past the deadline it is one closed missed sitting that
                  says why, and the wake is armed again"
          (at 900)
          (is (= 0 (count (filter #(= (str seat) (str (get-in % [:data :seat])))
                                  (missed)))))
          (is (<= 1 (wakes/sweep-unsent! *eng*)))
          (let [rows (missed)
                d (:data (first rows))]
            (is (= 1 (count rows)))
            (is (= :closed (:state (first rows))))
            (is (= "missed" (some-> (:closed_by d) name)))
            (is (= "never_sat" (some-> (:outcome d) name)))
            (is (str/includes? (str (:note d)) "never sent to a runner")))
          (is (true? (get-in (sched-of seat) [:data :wake_pending]))
              "so the queue fires again under the usual damper"))

        (testing "the sweep over the same state makes no second row"
          (wakes/sweep-unsent! *eng*)
          (is (= 1 (count (missed)))))

        (seat-do! seat :retire)))))

;; ── the release into an empty queue ──────────────────────────────────
;;
;; A wake that arrived during a sitting, often from that sitting's own
;; `complete`, is released when the sitting closes. When that sitting
;; took the last row, a release would fire a run into an empty queue.
;; Each seat walks `wake_item` under a batch of its own, so a
;; neighbour's rows do not move the count.

(defn- walk-item-seat!
  "A seat that walks `wake_item` under one batch, linked."
  [nm batch fire-cursor]
  (linked-seat! nm
                {:walk "wake_item"
                 :scope [{:kind "wake_item" :actions ["complete"]
                          :filter {:batch batch}}]}
                fire-cursor))

(defn- complete-inside-a-sitting!
  "Open a sitting, complete the row inside it, drain, close, drain.
  → whether the match was pending while the sitting was open."
  [wn seat item-id]
  (let [open-one (sitting! seat)]
    (item-do! item-id :complete)
    (drain-wakes! wn)
    (let [pending? (true? (get-in (sched-of seat) [:data :wake_pending]))]
      (close-sitting! open-one)
      (drain-wakes! wn)
      pending?)))

(deftest a-pending-wake-released-into-an-empty-queue-fires-nothing
  (let [wn :wake-empty-release
        fn' :wake-empty-release-fires
        _ (drain-fires! fn')
        emptied (item! "empty-release-last")
        left-a (item! "empty-release-one-left")
        _left-b (item! "empty-release-one-left")
        counted (item! "empty-release-no-walk")
        ;; seeded after the rows are born, so only what follows is heard
        _ (drain-wakes! wn)]

    (testing "the sitting that completed the last row leaves no fire
              behind it, and the pending wake is cleared"
      (let [{:keys [seat token]}
            (walk-item-seat! "emptyreleaseclerk" "empty-release-last" fn')]
        (is (true? (complete-inside-a-sitting! wn seat emptied))
            "the completion was damped by the open sitting")
        (is (empty? (seat-fires seat)))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (empty? (seat-fires seat)) "nor does the tick fire it later")
        (is (empty? (fires-of token)))
        (seat-do! seat :retire)))

    (testing "the same seat with one row still open gets exactly one fire"
      (let [{:keys [seat token]}
            (walk-item-seat! "oneleftclerk" "empty-release-one-left" fn')]
        (is (true? (complete-inside-a-sitting! wn seat left-a)))
        (let [ts (seat-fires seat)]
          (is (= 1 (count ts)))
          (is (nil? (get-in (first ts) [:inputs :text]))))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))
        (drain-fires! fn')
        (is (= 1 (count (fires-of token))))
        (seat-do! seat :retire)))

    (testing "a seat with no walk fires as before, whatever is left"
      (let [{:keys [seat token]}
            (linked-seat! "nowalkclerk"
                          {:scope count-scope
                           :wake_on [{:kind "wake_item" :actions ["complete"]
                                      :filter {:batch "empty-release-no-walk"}}]}
                          fn')]
        (is (true? (complete-inside-a-sitting! wn seat counted)))
        (is (= 1 (count (seat-fires seat))))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))
        (drain-fires! fn')
        (is (= 1 (count (fires-of token))))
        (seat-do! seat :retire)))))

;; ── the wake that meets a row still in its grace ────────────────────
;;
;; Production, 2026-09-29, three times: a reopen landed seconds after
;; the seat's last sitting closed. That sitting still held the row
;; through `release_grace_seconds`, so the walk read as empty and the
;; wake was spent on nothing. The wake is now due when the grace ends,
;; and the tick fires it then, once, into a walk that hands the row.

(defn- leased!
  "Hold `row-ids` of the seat's walk for the sitting `row` through
  leases, as a sit's claim does: following it, or ending at `until`."
  [tx row seat row-ids until]
  (doseq [id row-ids]
    (inv/insert-quiet! *eng* tx :lease
                       (cond-> {:holder_kind "sitting" :holder_id (str (:id row))
                                :subject_kind (str (get-in seat [:data :walk]))
                                :subject_id (str id)
                                :seat (str (:id seat))}
                         until (assoc :expires_at until))
                       {:principal seats/seats-actor})))

(defn- walked-and-ended!
  "Write what a real sit and close leave on a sitting: a lease on each
  row it walked, released to the end of its seat's grace, and the
  moment it ended."
  [sitting-id row-ids ^Instant ended]
  (let [st (:storage *eng*)
        row (raw :sitting sitting-id)
        seat (raw :seat (str (get-in row [:data :seat])))
        grace (long (or (get-in seat [:data :release_grace_seconds])
                        @#'seats/release-grace-default))]
    (store/with-tx st
      (fn [tx]
        (leased! tx row seat row-ids (.plusSeconds ended grace))
        (store/update-data! st tx :sitting (str sitting-id)
                            (assoc (:data row) :ended_at (str ended))
                            (:next-flip-at row))))))

(deftest a-wake-inside-the-release-grace-is-deferred-to-its-end
  (let [wn :wake-grace
        fn' :wake-grace-fires
        _ (drain-fires! fn')
        item (item! "grace-reopened")
        _ (drain-wakes! wn)
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat token]}
            (linked-seat! "graceclerk"
                          {:walk "wake_item"
                           :scope [{:kind "wake_item" :actions ["complete" "touch"]
                                    :filter {:batch "grace-reopened"}}]
                           :wake_on [{:kind "wake_item" :actions ["touch"]
                                      :filter {:batch "grace-reopened"}}]}
                          fn')
            closed (sitting! seat)]
        (close-sitting! closed)
        (drain-wakes! wn)
        (walked-and-ended! closed [item] t0)

        (testing "a reopen 10 s after the close fires nothing, and the
                  wake is due when the grace ends"
          (at 10)
          (item-do! item :touch)
          (drain-wakes! wn)
          (is (empty? (seat-fires seat)))
          (is (true? (get-in (sched-of seat) [:data :wake_pending]))
              "the wake is remembered, not spent")
          (is (= (.plusSeconds t0 120) (due-of seat))))

        (testing "inside the grace the tick still fires nothing"
          (at 60)
          (wakes/tick! *eng*)
          (is (empty? (seat-fires seat))))

        (testing "at the grace's end one fire goes out, naming no row, and
                  the walk hands the row"
          (at 121)
          (wakes/tick! *eng*)
          (let [ts (seat-fires seat)]
            (is (= 1 (count ts)))
            (is (nil? (get-in (first ts) [:inputs :text]))))
          (is (not (get-in (sched-of seat) [:data :wake_pending])))
          (is (not (contains? (seats/unwalkable-rows *eng* (raw :seat seat) nil)
                              (str item))))
          (drain-fires! fn')
          (is (= 1 (count (fires-of token))))
          (wakes/tick! *eng*)
          (is (= 1 (count (seat-fires seat))) "and it fires once"))
        (seat-do! seat :retire)))))

;; ── the fire that names a row still in its grace ────────────────────
;;
;; Production, 2026-09-30: qa's sitting closed at 17:33:46 and qa was
;; fired at 17:34 with text naming the row that sitting walked. The run
;; sat, was told the row was held, and stopped, and the fire's text was
;; spent. Such a fire now waits for the grace to lift, and goes out then
;; with its own text (ticket afb445d4).

(defn- fire-with! [seat-id text]
  (inv/invoke! *eng* :seat (str seat-id) :fire {:text text}
               {:principal elena
                :idempotency-key (str "grace-fire:" (random-uuid))}))

(deftest a-fire-naming-a-row-in-its-grace-waits-for-the-grace-to-lift
  (let [fn' :fire-grace-fires
        _ (drain-fires! fn')
        item (item! "grace-fired")
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))
        text (str "Walk " item " again.")]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat token]}
            (linked-seat! "gracefired"
                          {:walk "wake_item"
                           :scope [{:kind "wake_item" :actions ["complete" "touch"]
                                    :filter {:batch "grace-fired"}}]}
                          fn')
            closed (sitting! seat)]
        (close-sitting! closed)
        (walked-and-ended! closed [item] t0)
        (drain-fires! fn')

        (testing "a fire 10 s after the close sends nothing, and waits with
                  its text until the grace ends"
          (at 10)
          (fire-with! seat text)
          (drain-fires! fn')
          (is (empty? (fires-of token)))
          (is (true? (get-in (sched-of seat) [:data :wake_pending])))
          (is (= text (get-in (sched-of seat) [:data :wake_text])))
          (is (= (.plusSeconds t0 120) (due-of seat))))

        (testing "inside the grace the tick still sends nothing"
          (at 60)
          (wakes/tick! *eng*)
          (drain-fires! fn')
          (is (empty? (fires-of token))))

        (testing "when the grace lifts the run starts, with the fire's text"
          (at 121)
          (wakes/tick! *eng*)
          (drain-fires! fn')
          (let [fs (fires-of token)]
            (is (= 1 (count fs)))
            (is (clojure.string/includes? (str (:text (first fs))) (str item))))
          (is (= text (get-in (last (seat-fires seat)) [:inputs :text])))
          (is (not (get-in (sched-of seat) [:data :wake_pending])))
          (is (nil? (get-in (sched-of seat) [:data :wake_text]))))
        (seat-do! seat :retire)))))

;; Production, 2026-10-22: engine-engineer (max_open_sittings 2) was
;; fired 21 s after a close, with text naming the row that sitting
;; walked. The fire waited out the grace, and the fire that waited was
;; read as a run on its way to a sit, so the release found its own row
;; spoken for, cleared the wake as `rows_taken`, and nothing went out;
;; ten minutes later the sweep wrote a sitting that never sat (ticket
;; 2b128405).

(deftest a-fire-waiting-out-a-grace-goes-out-on-a-seat-of-several-slots
  (let [fn' :fire-grace-slots-fires
        _ (drain-fires! fn')
        item (item! "grace-slots")
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))
        text (str "Walk " item " again.")]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat token]}
            (linked-seat! "graceslots"
                          {:walk "wake_item"
                           :scope [{:kind "wake_item" :actions ["complete" "touch"]
                                    :filter {:batch "grace-slots"}}]
                           :max_open_sittings 2}
                          fn')
            closed (sitting! seat)]
        (close-sitting! closed)
        (walked-and-ended! closed [item] t0)
        (drain-fires! fn')

        (testing "a fire 21 s after the close sends nothing, and waits"
          (at 21)
          (fire-with! seat text)
          (drain-fires! fn')
          (is (empty? (fires-of token)))
          (is (= [text] (vec (get-in (sched-of seat) [:data :wake_texts])))))

        (testing "when the grace lifts the run starts, with the fire's text"
          (at 121)
          (wakes/tick! *eng*)
          (drain-fires! fn')
          (let [fs (fires-of token)]
            (is (= 1 (count fs)))
            (is (clojure.string/includes? (str (:text (first fs))) (str item))))
          (is (= text (get-in (last (seat-fires seat)) [:inputs :text])))
          (is (not (get-in (sched-of seat) [:data :wake_pending])))
          (is (nil? (get-in (sched-of seat) [:data :last_silent_wake]))))
        (seat-do! seat :retire)))))

;; ── more than one wake waiting out one grace ────────────────────────
;;
;; `wake_text` held one string, so a second fire deferred in the same
;; grace took the first's words, and a text beside a waiting wake of no
;; text turned that queue walk into a one-row run. Each waiting text now
;; goes out as its own run, the wake of no text as its own fire, and the
;; schedule row says that a fire waits and until when (ticket 58573175).

(deftest two-fires-deferred-in-one-grace-each-go-out-with-their-own-text
  (let [fn' :fire-grace-two-fires
        _ (drain-fires! fn')
        one (item! "grace-two")
        two (item! "grace-two")
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))
        text-one (str "Walk " one " again.")
        text-two (str "Walk " two " again.")]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat token]}
            (linked-seat! "gracetwo"
                          {:walk "wake_item"
                           :scope [{:kind "wake_item" :actions ["complete" "touch"]
                                    :filter {:batch "grace-two"}}]}
                          fn')
            closed (sitting! seat)]
        (close-sitting! closed)
        (walked-and-ended! closed [one two] t0)
        (drain-fires! fn')

        (testing "two fires inside the grace send nothing, and both texts
                  wait, each one time"
          (at 10)
          (fire-with! seat text-one)
          (drain-fires! fn')
          (at 20)
          (fire-with! seat text-two)
          (fire-with! seat text-two)
          (drain-fires! fn')
          (is (empty? (fires-of token)))
          (is (= [text-one text-two]
                 (vec (get-in (sched-of seat) [:data :wake_texts]))))
          (is (not (get-in (sched-of seat) [:data :wake_textless])))
          (is (= (.plusSeconds t0 120) (due-of seat))))

        (testing "when the grace lifts each goes out as its own run, with
                  its own text"
          (at 121)
          (wakes/tick! *eng*)
          (drain-fires! fn')
          (is (= [text-one text-two]
                 (mapv #(get-in % [:inputs :text])
                       (take-last 2 (seat-fires seat)))))
          (let [fs (fires-of token)]
            (is (= 2 (count fs)))
            (is (clojure.string/includes? (str (:text (first fs))) (str one)))
            (is (clojure.string/includes? (str (:text (second fs))) (str two))))
          (is (not (get-in (sched-of seat) [:data :wake_pending])))
          (is (nil? (get-in (sched-of seat) [:data :wake_texts])))
          (is (nil? (get-in (sched-of seat) [:data :wake_text]))))
        (seat-do! seat :retire)))))

(deftest a-damped-wake-of-no-text-still-fires-textless-beside-a-deferred-text
  (let [wn :wake-grace-textless
        fn' :wake-grace-textless-fires
        _ (drain-fires! fn')
        item (item! "grace-textless")
        _ (drain-wakes! wn)
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))
        text (str "Walk " item " again.")]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat token]}
            (linked-seat! "gracetextless"
                          {:walk "wake_item"
                           :scope [{:kind "wake_item" :actions ["complete" "touch"]
                                    :filter {:batch "grace-textless"}}]
                           :wake_on [{:kind "wake_item" :actions ["touch"]
                                      :filter {:batch "grace-textless"}}]}
                          fn')
            open (sitting! seat)]
        (testing "a match under an open sitting is damped: it waits, with
                  no text"
          (item-do! item :touch)
          (drain-wakes! wn)
          (is (empty? (seat-fires seat)))
          (is (true? (get-in (sched-of seat) [:data :wake_pending])))
          (is (empty? (sch/waiting-texts (:data (sched-of seat))))))

        (testing "the sitting closes having walked the row: the wake waits
                  for the grace"
          (close-sitting! open)
          (walked-and-ended! open [item] t0)
          (drain-wakes! wn)
          (is (empty? (seat-fires seat)))
          (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

        (testing "a fire naming the held row waits beside it, and the wake
                  of no text is kept as its own"
          (at 10)
          (fire-with! seat text)
          (drain-fires! fn')
          (is (empty? (fires-of token)))
          (is (= [text] (vec (get-in (sched-of seat) [:data :wake_texts]))))
          (is (true? (get-in (sched-of seat) [:data :wake_textless]))))

        (testing "when the grace lifts two runs start: the fire's text, and
                  the queue walk with none"
          (at 121)
          (wakes/tick! *eng*)
          (drain-fires! fn')
          (is (= [text nil]
                 (mapv #(get-in % [:inputs :text])
                       (take-last 2 (seat-fires seat)))))
          (is (= 2 (count (fires-of token))))
          (is (not (get-in (sched-of seat) [:data :wake_pending])))
          (is (not (get-in (sched-of seat) [:data :wake_textless]))))
        (seat-do! seat :retire)))))

(deftest a-deferred-fire-says-on-the-schedule-row-that-it-waits
  (let [fn' :fire-grace-note-fires
        _ (drain-fires! fn')
        item (item! "grace-note")
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))
        text (str "Walk " item " again.")]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat token]}
            (linked-seat! "gracenote"
                          {:walk "wake_item"
                           :scope [{:kind "wake_item" :actions ["complete" "touch"]
                                    :filter {:batch "grace-note"}}]}
                          fn')
            closed (sitting! seat)]
        (close-sitting! closed)
        (walked-and-ended! closed [item] t0)
        (drain-fires! fn')

        (testing "a person's fire that waits leaves a note that says so,
                  and until when"
          (at 10)
          (fire-with! seat text)
          (drain-fires! fn')
          (is (empty? (fires-of token)))
          (let [note (str (get-in (sched-of seat) [:data :note]))]
            (is (clojure.string/starts-with? note "Waiting for "))
            (is (clojure.string/includes? note (str (.plusSeconds t0 120))))))

        (testing "the note is cleared when the fire goes out"
          (at 121)
          (wakes/tick! *eng*)
          (drain-fires! fn')
          (is (= 1 (count (fires-of token))))
          (is (nil? (get-in (sched-of seat) [:data :note]))))
        (seat-do! seat :retire)))))

(deftest a-fire-naming-a-free-row-starts-at-once
  (let [fn' :fire-free-fires
        _ (drain-fires! fn')
        held (item! "grace-free")
        free (item! "grace-free")
        {:keys [seat token]}
        (linked-seat! "gracefree"
                      {:walk "wake_item"
                       :scope [{:kind "wake_item" :actions ["complete" "touch"]
                                :filter {:batch "grace-free"}}]}
                      fn')
        closed (sitting! seat)]
    (close-sitting! closed)
    (walked-and-ended! closed [held] (Instant/now))
    (drain-fires! fn')
    (fire-with! seat (str "Walk " free "."))
    (drain-fires! fn')
    (is (= 1 (count (fires-of token))))
    (is (not (get-in (sched-of seat) [:data :wake_pending])))
    (seat-do! seat :retire)))

;; A fire that goes out stamps the schedule `fired`, and that stamp
;; dropped every waiting text: a fire naming a free row wiped the one
;; that waited for a held row's grace, and it never went out (ticket
;; c84f747a).

(deftest a-fire-that-goes-out-during-a-grace-keeps-the-fire-that-waits
  (let [fn' :fire-grace-kept-fires
        _ (drain-fires! fn')
        held (item! "grace-kept")
        free (item! "grace-kept")
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))
        text-held (str "Walk " held " again.")
        text-free (str "Walk " free ".")]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat token]}
            (linked-seat! "gracekept"
                          {:walk "wake_item"
                           :scope [{:kind "wake_item" :actions ["complete" "touch"]
                                    :filter {:batch "grace-kept"}}]
                           ;; the second fire starts the seat's gap, and
                           ;; the gap is not what this test is about
                           :fire_interval_seconds 1}
                          fn')
            closed (sitting! seat)]
        (close-sitting! closed)
        (walked-and-ended! closed [held] t0)
        (drain-fires! fn')

        (testing "a fire naming the held row waits"
          (at 10)
          (fire-with! seat text-held)
          (drain-fires! fn')
          (is (empty? (fires-of token)))
          (is (= [text-held]
                 (vec (get-in (sched-of seat) [:data :wake_texts])))))

        (testing "a fire naming a free row goes out at once, and the first
                  still waits"
          (at 20)
          (fire-with! seat text-free)
          (drain-fires! fn')
          (let [fs (fires-of token)]
            (is (= 1 (count fs)))
            (is (clojure.string/includes? (str (:text (first fs))) (str free))))
          (is (true? (get-in (sched-of seat) [:data :wake_pending])))
          (is (= [text-held]
                 (vec (get-in (sched-of seat) [:data :wake_texts]))))
          (is (= text-held (get-in (sched-of seat) [:data :wake_text])))
          (is (= (.plusSeconds t0 120) (due-of seat)))
          (is (clojure.string/starts-with?
               (str (get-in (sched-of seat) [:data :note])) "Waiting for ")))

        (testing "when the grace lifts the first goes out, with its own text"
          (at 121)
          (wakes/tick! *eng*)
          (drain-fires! fn')
          (let [fs (fires-of token)]
            (is (= 2 (count fs)))
            (is (clojure.string/includes? (str (:text (second fs))) (str held))))
          (is (= text-held (get-in (last (seat-fires seat)) [:inputs :text])))
          (is (not (get-in (sched-of seat) [:data :wake_pending])))
          (is (nil? (get-in (sched-of seat) [:data :wake_texts]))))
        (seat-do! seat :retire)))))

;; ── the fuel wall holds the wake ─────────────────────────────────────
;;
;; Production, 2026-09-27: code-seat was past its week's fuel and a
;; wake still fired it. The run's grant scoped to nothing, the sit
;; handed it no walk, and the run told a person the seat was broken.
;; A wake asks the router's wall before it fires; a wake the wall holds
;; waits as `wake_pending`, and the first release after the window
;; rolls fires it.

(deftest a-seat-past-its-week-of-fuel-is-not-woken-until-the-window-rolls
  (let [wn :wake-fuel-wall
        fn' :wake-fuel-wall-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (linked-seat! "fuelclerk"
                      {:budget_usd_per_week 0.001M
                       :wake_on [{:kind "wake_task" :actions ["complete"]}]}
                      fn')
        ;; one closed sitting spends more than the week holds
        _ (close-sitting! (sitting! seat))
        _ (drain-wakes! wn)
        now (Instant/now)]

    (testing "the seat is at the wall"
      (is (true? (wakes/at-the-fuel-wall? *eng* (raw :seat seat) now))))

    (testing "a matching transition fires nothing: the wake waits, and
              the schedule says the budget held it"
      (task-do! (task! "a thing past the budget") :complete)
      (drain-wakes! wn)
      (is (empty? (seat-fires seat)))
      (is (true? (get-in (sched-of seat) [:data :wake_pending])))
      (is (some? (get-in (sched-of seat) [:data :last_halted_wake])))
      (wakes/sweep-pending! *eng*)
      (is (empty? (seat-fires seat)) "nor does the tick fire it while the wall holds")
      (drain-fires! fn')
      (is (empty? (fires-of token))))

    (testing "once the window rolls past the sitting, the pending wake fires"
      (let [later (.plusSeconds now (* 8 86400))
            rolled (assoc *eng* :now-fn (constantly later))]
        (is (false? (wakes/at-the-fuel-wall? rolled (raw :seat seat) later)))
        (wakes/sweep-pending! rolled)
        (let [ts (seat-fires seat)]
          (is (= 1 (count ts)))
          (is (nil? (get-in (first ts) [:inputs :text]))
              "a release names no row, so the session walks the queue"))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))))

    (seat-do! seat :retire)))

;; The hold wrote `wake_pending` and `last_halted_wake` and left
;; `halted` to a halted sit. A seat whose wakes are all held never
;; sits, so its schedule read as not halted and a person could not see
;; why nothing ran. The hold stamps `halted` itself, in the sit's
;; spelling, and the first fire that goes out takes it off.

(deftest a-wake-held-at-the-wall-stamps-the-schedule-halted
  (let [wn :wake-wall-halted
        fn' :wake-wall-halted-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (linked-seat! "wallclerk"
                      {:budget_usd_per_week 0.001M
                       :wake_on [{:kind "wake_task" :actions ["complete"]}]}
                      fn')
        ;; one closed sitting spends more than the week holds
        _ (close-sitting! (sitting! seat))
        _ (drain-wakes! wn)
        now (Instant/now)
        halted #(get-in (sched-of seat) [:data :halted])]

    (testing "a wake held at the wall stamps `halted` with the reason"
      (task-do! (task! "a thing the wall holds") :complete)
      (drain-wakes! wn)
      (is (empty? (seat-fires seat)))
      (is (= "budget" (:wall (halted))))
      (is (re-find #"The week's fuel is spent" (str (:detail (halted))))))

    (testing "the seat itself is marked halted budget_reached, once"
      (task-do! (task! "another thing the wall holds") :complete)
      (drain-wakes! wn)
      (is (empty? (seat-fires seat)))
      (is (= "budget_reached" (get-in (raw :seat seat) [:data :halt :reason])))
      (is (<= (count (get-in (raw :seat seat) [:data :halt :detail])) 240))
      (is (= 1 (count (filter #(= :mark_halted (:action %))
                              (log-of :seat seat))))
          "one mark_halted across repeated holds"))

    (testing "the first fire after the wall lifts clears it"
      (let [later (.plusSeconds now (* 8 86400))
            rolled (assoc *eng* :now-fn (constantly later))]
        (wakes/sweep-pending! rolled)
        (is (= 1 (count (seat-fires seat))))
        (is (nil? (halted)))))

    (seat-do! seat :retire)))

;; ── a wake the fire door refuses waits ───────────────────────────────
;;
;; A halt line other than the budget's is judged by the `fire` door
;; itself. Its refusal used to drop the match: nothing set
;; `wake_pending`, so when a person lifted the line the rows that came
;; in meanwhile never woke the seat.

(deftest a-wake-the-fire-door-refuses-is-left-pending
  (let [wn :wake-halt-wall
        fn' :wake-halt-wall-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (door-seat! "haltclerk" {}
                    [{:kind "wake_task" :actions ["complete"]}]
                    fn')]
    (is (true? (seats/seat-halt! *eng* seat "model_not_held"
                                 "This session declares nothing and haltclerk is held for 1 model(s).")))

    (testing "a matching transition fires nothing, and the wake waits"
      (task-do! (task! "a thing behind the halt") :complete)
      (drain-doors!)
      (is (empty? (seat-fires seat)))
      (is (true? (get-in (sched-of seat) [:data :wake_pending])))
      (drain-fires! fn')
      (is (empty? (fires-of token))))

    (seat-do! seat :retire)))

;; ── the cadence a chair's Routine does not keep ─────────────────────

(defn- stamp-last-fired!
  "The provider's stamp, written as the schedules consumer writes it."
  [seat-id ^Instant at]
  (let [row (sched-of seat-id)]
    (store/with-tx (:storage *eng*)
      (fn [tx]
        (store/update-data! (:storage *eng*) tx :schedule (:id row)
                            (assoc (:data row) :last_fired_at (str at))
                            (:next-flip-at row))))))

(deftest a-chair-linked-seat-fires-on-its-cadence-and-an-own-linked-one-does-not
  (let [fn' :wake-cadence-fires
        _ (drain-fires! fn')
        model (model! "cadence-chair")
        _ (inv/invoke! *eng* :model (str model) :link
                       {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                       "/routines/trig_cadencechair/fire")
                        :token "rk-test-cadencechair-0123456789abcdef"}
                       {:principal elena})
        chaired (seat! "cadencechaired" {:held_for [(str model)]})
        _ (drain-fires! fn')
        now (Instant/now)
        at-now (assoc *eng* :now-fn (constantly now))]
    (stamp-last-fired! chaired (.minusSeconds now 7200))

    (testing "a chair-linked seat of cadence 3600 whose schedule last
              fired two hours ago is fired once by the tick"
      (is (sch/linked? *eng* (sched-of chaired)))
      (is (not (sch/linked? (sched-of chaired))))
      (wakes/tick! at-now)
      (is (= 1 (count (seat-fires chaired))))
      (is (nil? (get-in (first (seat-fires chaired)) [:inputs :text]))
          "a cadence fire names no row, so the session walks the queue")
      (is (not (get-in (sched-of chaired) [:data :wake_pending]))))

    (testing "and not again inside the cadence"
      (wakes/tick! at-now)
      (wakes/tick! (assoc *eng* :now-fn (constantly (.plusSeconds now 1800))))
      (is (= 1 (count (seat-fires chaired)))))

    (testing "an own-linked schedule is left to its provider's cron"
      (let [{:keys [seat]} (linked-seat! "cadenceown" {} fn')]
        (stamp-last-fired! seat (.minusSeconds now 7200))
        (wakes/tick! at-now)
        (is (empty? (seat-fires seat)))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))
        (seat-do! seat :retire)))

    (seat-do! chaired :retire)))

(deftest a-pool-only-seat-is-owed-its-cadence
  ;; waymark ticket 962e0aeb: a schedule whose only way out is a runner
  ;; pool, its own or its chair's, has no copy whose cron keeps its
  ;; cadence, so the sweep marks it owed as it does a chair-linked one
  (let [fn' :wake-cadence-pool
        _ (drain-fires! fn')
        runner! (fn [nm]
                  (str (:id (:row (inv/create! *eng* :runner_link
                                               {:provider "claude_routine"
                                                :fire_url (str "https://api.anthropic.com/v1/claude_code"
                                                               "/routines/trig_" nm "/fire")
                                                :fire_token (str "rk-test-" nm "-0123456789abcdef")}
                                               {:principal elena})))))
        pooled (model! "cadence-pool-chair")
        _ (inv/invoke! *eng* :model (str pooled) :set_runners
                       {:runners [(runner! "cadencepoolchair")]}
                       {:principal elena})
        bare (model! "cadence-bare-chair")
        chair-pooled (seat! "cadencechairpool" {:held_for [(str pooled)]})
        own-pooled (seat! "cadenceownpool" {:held_for [(str bare)]})
        _ (drain-fires! fn')
        _ (inv/invoke! *eng* :schedule (str (:id (sched-of own-pooled))) :set_runners
                       {:runners [(runner! "cadenceownpool")]}
                       {:principal elena})
        _ (drain-fires! fn')
        seats [chair-pooled own-pooled]
        now (Instant/now)
        at-now (assoc *eng* :now-fn (constantly now))]
    (doseq [s seats] (stamp-last-fired! s (.minusSeconds now 7200)))
    (try
      (doseq [s seats]
        (is (not (sch/linked? *eng* (sched-of s)))
            "neither the row nor its chair holds a link")
        (is (sch/fires-out? *eng* (sched-of s))))

      (testing "the sweep marks the owed cadence of both pool-only schedules"
        (wakes/sweep-cadence! at-now)
        (doseq [s seats]
          (is (true? (get-in (sched-of s) [:data :wake_pending])))))
      (finally
        (doseq [s seats] (seat-do! s :retire))))))

;; ── many door matches in one gap are one run (ticket 3fc968ed) ──────
;;
;; Production, 2026-10-24: ops-seat (one slot, a 300 s gap) was fired 20
;; times in 32 s by its door subscription, one run for each waiting text.
;; A one-slot release now sends one fire, naming no row when more than
;; one text waited.

(deftest many-door-matches-in-one-gap-release-one-fire-of-no-text
  (let [wn :wake-many-doors
        fn' :wake-many-doors-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (door-seat! "manydoorclerk" {:fire_interval_seconds 1}
                    [{:kind "wake_task" :actions ["complete"]}]
                    fn')]
    (try
      (task-do! (task! "the first match") :complete)
      (drain-doors!)
      (drain-fires! fn')
      (is (= 1 (count (seat-fires seat))))

      (testing "twenty matches inside the gap wait, and the release sends
                one fire that names no row"
        (dotimes [i 20]
          (task-do! (task! (str "match " i)) :complete))
        (doors/drain! *eng*)
        (is (= 1 (count (seat-fires seat))) "the gap holds them")
        (Thread/sleep 1100)
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 2 (count (seat-fires seat))))
        (is (= 2 (count (fires-of token))))
        (is (nil? (get-in (last (seat-fires seat)) [:inputs :text])))
        (let [row (sched-of seat)]
          (is (not (get-in row [:data :wake_pending])))
          (is (nil? (get-in row [:data :wake_texts])))
          (is (nil? (get-in row [:data :wake_text])))))

      (testing "one match waiting still fires naming its row"
        (let [one (task! "the lone match")]
          (task-do! one :complete)
          (doors/drain! *eng*)
          (Thread/sleep 1100)
          (wakes/sweep-pending! *eng*)
          (drain-fires! fn')
          (is (= 3 (count (seat-fires seat))))
          (is (str/includes? (str (get-in (last (seat-fires seat)) [:inputs :text]))
                             (str one)))))
      (finally
        (seat-do! seat :retire)))))

;; ── a broken schedule holds its wakes (waymark ticket bb19404d) ─────

(deftest a-broken-schedule-holds-its-wake-until-it-is-linked-again
  (let [wn :wake-broken
        fn' :wake-broken-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (door-seat! "brokenclerk" {:fire_interval_seconds 1}
                    [{:kind "wake_task" :actions ["complete"]}]
                    fn')]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the provider refuses") :complete)
      (drain-doors!)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of token))) "the refused POST went out once")
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the broken row fires nothing and stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while it is broken") :complete)
        (doors/drain! *eng*)
        (wakes/sweep-pending! *eng*)
        (wakes/tick! *eng*)
        (drain-fires! fn')
        (is (= 1 (count (seat-fires seat))))
        (is (= 1 (count (fires-of token))))
        (let [row (sched-of seat)]
          (is (= :broken (:state row)))
          (is (true? (get-in row [:data :wake_pending])))
          (is (seq (get-in row [:data :wake_heard])))))

      (testing "linking it again releases the held wake once"
        (inv/invoke! *eng* :schedule (str (:id (sched-of seat))) :link
                     {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                     "/routines/trig_brokenclerk/fire")
                      :token token}
                     {:principal elena})
        (drain-fires! fn')
        (drain-fires! fn')
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 2 (count (seat-fires seat))))
        (is (= 2 (count (fires-of token))))
        (let [row (sched-of seat)]
          (is (= :live (:state row)))
          (is (not (get-in row [:data :wake_pending])))))
      (finally
        (sch/answer! *fire* nil)
        (seat-do! seat :retire)))))

(deftest a-broken-chair-linked-schedule-skips-its-cadence
  (let [fn' :wake-broken-cadence-fires
        _ (drain-fires! fn')
        model (model! "broken-cadence-chair")
        _ (inv/invoke! *eng* :model (str model) :link
                       {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                       "/routines/trig_brokencadence/fire")
                        :token "rk-test-brokencadence-0123456789abcdef"}
                       {:principal elena})
        chaired (seat! "brokencadenced" {:held_for [(str model)]})
        _ (drain-fires! fn')
        now (Instant/now)]
    (try
      (stamp-last-fired! chaired (.minusSeconds now 7200))
      (sch/answer! *fire* 401)
      (wakes/tick! (assoc *eng* :now-fn (constantly now)))
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (seat-fires chaired))))
      (is (= :broken (:state (sched-of chaired))))

      (testing "a cadence tick on the broken row is skipped, not queued"
        (dotimes [_ 3]
          (wakes/tick! (assoc *eng* :now-fn (constantly (.plusSeconds now 7200)))))
        (drain-fires! fn')
        (is (= 1 (count (seat-fires chaired))))
        (is (not (get-in (sched-of chaired) [:data :wake_pending]))))
      (finally
        (sch/answer! *fire* nil)
        (seat-do! chaired :retire)))))

(deftest a-broken-chair-linked-schedule-is-released-when-its-model-is-linked-again
  ;; waymark ticket 6e407ca7
  (let [wn :wake-chair-relinked
        fn' :wake-chair-relinked-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        token "rk-test-chairrelinked-0123456789abcdef"
        rotated "rk-test-chairrotated-0123456789abcdef"
        model (model! "chair-relinked-chair")
        link-model! #(inv/invoke! *eng* :model (str model) :link
                                  {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                                  "/routines/trig_chairrelinked/fire")
                                   :token %}
                                  {:principal elena})
        _ (link-model! token)
        seat (seat! "chairrelinkedclerk"
                    {:held_for [(str model)]
                     :fire_interval_seconds 1})
        _ (subscribe! seat {:kind "wake_task" :actions ["complete"]})
        _ (drain-fires! fn')]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the chair's Routine refuses") :complete)
      (drain-doors!)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of token))) "the refused POST went out once")
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the broken row stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while the chair is broken") :complete)
        (doors/drain! *eng*)
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 1 (count (fires-of token))))
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

      (testing "linking the model again releases the held wake once"
        (link-model! rotated)
        (drain-fires! fn')
        (drain-fires! fn')
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 2 (count (seat-fires seat))))
        (is (= 1 (count (fires-of token))) "the refused token is not sent again")
        (is (= 1 (count (fires-of rotated))) "the held wake went out on the new link")
        (let [row (sched-of seat)]
          (is (= :live (:state row)))
          (is (not (get-in row [:data :wake_pending])))))
      (finally
        (sch/answer! *fire* nil)
        (seat-do! seat :retire)))))

(deftest a-held-schedule-is-released-when-its-runners-are-set
  ;; waymark ticket 6e407ca7
  (let [wn :wake-runners-set
        fn' :wake-runners-set-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (door-seat! "runnersetclerk" {:fire_interval_seconds 1}
                    [{:kind "wake_task" :actions ["complete"]}]
                    fn')
        runner-token "rk-test-runnersetrunner-0123456789abcdef"]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the provider refuses this seat") :complete)
      (drain-doors!)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the broken row stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while the seat is broken") :complete)
        (doors/drain! *eng*)
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 1 (count (seat-fires seat))))
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

      (testing "naming a live runner releases the held wake once"
        (let [runner (str (:id (:row (inv/create! *eng* :runner_link
                                                   {:provider "claude_routine"
                                                    :fire_url (str "https://api.anthropic.com/v1/claude_code"
                                                                   "/routines/trig_runnersetrunner/fire")
                                                    :fire_token runner-token}
                                                   {:principal elena}))))]
          (inv/invoke! *eng* :schedule (str (:id (sched-of seat))) :set_runners
                       {:runners [runner]}
                       {:principal elena})
          (drain-fires! fn')
          (drain-fires! fn')
          (wakes/sweep-pending! *eng*)
          (drain-fires! fn')
          (is (= 2 (count (seat-fires seat))))
          (is (= 1 (count (fires-of runner-token))) "the fire went through the runner")
          (is (= 1 (count (fires-of token))) "the broken link was not tried again")
          (let [row (sched-of seat)]
            (is (= :live (:state row)))
            (is (not (get-in row [:data :wake_pending]))))))
      (finally
        (sch/answer! *fire* nil)
        (seat-do! seat :retire)))))

(deftest a-held-schedule-is-released-when-its-chairs-runners-are-set
  ;; waymark ticket 13d01ec3
  (let [wn :wake-chair-runners-set
        fn' :wake-chair-runners-set-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        token "rk-test-chairpoollink-0123456789abcdef"
        dead-token "rk-test-chairpooldead-0123456789abcdef"
        live-token "rk-test-chairpoollive-0123456789abcdef"
        runner! #(str (:id (:row (inv/create! *eng* :runner_link
                                              {:provider "claude_routine"
                                               :fire_url (str "https://api.anthropic.com/v1/claude_code"
                                                              "/routines/trig_" %1 "/fire")
                                               :fire_token %2}
                                              {:principal elena}))))
        set-runners! #(inv/invoke! *eng* :model (str %1) :set_runners
                                   {:runners %2}
                                   {:principal elena})
        model (model! "chair-pool-chair")
        _ (inv/invoke! *eng* :model (str model) :link
                       {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                       "/routines/trig_chairpoollink/fire")
                        :token token}
                       {:principal elena})
        dead (runner! "chairpooldead" dead-token)
        _ (set-runners! model [dead])
        seat (seat! "chairpoolclerk"
                    {:held_for [(str model)]
                     :fire_interval_seconds 1})
        _ (subscribe! seat {:kind "wake_task" :actions ["complete"]})
        _ (drain-fires! fn')]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the pool's only runner refuses") :complete)
      (drain-doors!)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of dead-token))) "the refused POST went out once")
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the held row stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while the pool is broken") :complete)
        (doors/drain! *eng*)
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 1 (count (fires-of dead-token))))
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

      (testing "naming a live runner on the model releases the held wake once"
        (let [live (runner! "chairpoollive" live-token)]
          (set-runners! model [dead live])
          (drain-fires! fn')
          (drain-fires! fn')
          (drain-fires! fn')
          (is (= 1 (count (fires-of live-token))) "the held wake went out through the live runner, with no sweep")
          (is (= 1 (count (fires-of dead-token))) "the broken runner was not tried again")
          (is (zero? (count (fires-of token))) "the model's own link was not used")
          (let [row (sched-of seat)]
            (is (= :live (:state row)))
            (is (not (get-in row [:data :wake_pending]))))))
      (finally
        (sch/answer! *fire* nil)
        (seat-do! seat :retire)))))

(deftest a-restated-link-mends-the-broken-schedule-its-pool-names
  ;; R-22.4 of docs/spec-seat.md: the engine's `mend`
  (let [wn :wake-mend-restated
        fn' :wake-mend-restated-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (door-seat! "mendclerk" {:fire_interval_seconds 1}
                    [{:kind "wake_task" :actions ["complete"]}]
                    fn')
        dead-token "rk-test-menddead-0123456789abcdef"
        fresh-token "rk-test-mendfresh-0123456789abcdef"
        dead (str (:id (:row (inv/create! *eng* :runner_link
                                          {:provider "claude_routine"
                                           :fire_url (str "https://api.anthropic.com/v1/claude_code"
                                                          "/routines/trig_menddead/fire")
                                           :fire_token dead-token}
                                          {:principal elena}))))
        sched-id (str (:id (sched-of seat)))
        _ (inv/invoke! *eng* :schedule sched-id :set_runners
                       {:runners [dead]} {:principal elena})
        _ (drain-fires! fn')
        mend! #(inv/invoke! *eng* :schedule sched-id :mend nil {:principal %})
        mends #(count (filter (comp #{:mend} :action) (log-of :schedule sched-id)))]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the pool's only runner refuses") :complete)
      (drain-doors!)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of dead-token))) "the refused POST went out once")
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the broken row stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while the pool is broken") :complete)
        (doors/drain! *eng*)
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 1 (count (fires-of dead-token))))
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

      (testing "mend is the engine's, and only while a link in the pool is live"
        (is (some? (refusal #(mend! elena))) "a person's mend is refused")
        (is (= :fires-through-a-live-link
               (:guard (refusal #(mend! sch/system-actor))))
            "the engine's mend with no live link is refused")
        (is (= :broken (:state (sched-of seat))))
        (is (zero? (mends))))

      (testing "restating the link mends the row through one mend and releases its wake"
        (let [link (store/with-tx (:storage *eng*)
                     (fn [tx] (store/load-row (:storage *eng*) tx :runner_link dead {})))]
          (inv/invoke! *eng* :runner_link dead :restate {:token fresh-token}
                       {:principal elena
                        :if-match (inv/etag :runner_link dead (:version link))}))
        (drain-fires! fn')
        (drain-fires! fn')
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 1 (mends)))
        (is (= 1 (count (fires-of fresh-token))) "the held wake went out through the restated link")
        (let [row (sched-of seat)]
          (is (= :live (:state row)))
          (is (not (get-in row [:data :wake_pending])))))
      (finally
        (sch/answer! *fire* nil)
        (seat-do! seat :retire)))))

(deftest a-new-link-mends-the-broken-schedule-its-pool-names
  ;; R-22.4 of docs/spec-seat.md: a link made, then given its pools
  (let [wn :wake-mend-new
        fn' :wake-mend-new-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (door-seat! "mendnewclerk" {:fire_interval_seconds 1}
                    [{:kind "wake_task" :actions ["complete"]}]
                    fn')
        dead-token "rk-test-mendnewdead-0123456789abcdef"
        new-token "rk-test-mendnewlink-0123456789abcdef"
        runner! #(str (:id (:row (inv/create! *eng* :runner_link
                                              {:provider "claude_routine"
                                               :fire_url (str "https://api.anthropic.com/v1/claude_code"
                                                              "/routines/trig_" %1 "/fire")
                                               :fire_token %2}
                                              {:principal elena}))))
        dead (runner! "mendnewdead" dead-token)
        sched-id (str (:id (sched-of seat)))
        _ (inv/invoke! *eng* :schedule sched-id :set_runners
                       {:runners [dead]} {:principal elena})
        _ (drain-fires! fn')
        mends #(count (filter (comp #{:mend} :action) (log-of :schedule sched-id)))]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the pool's only runner refuses") :complete)
      (drain-doors!)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of dead-token))) "the refused POST went out once")
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the broken row stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while the pool is broken") :complete)
        (doors/drain! *eng*)
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 1 (count (fires-of dead-token))))
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

      (let [fresh (runner! "mendnewlink" new-token)]
        (testing "a new link no broken row names mends nothing"
          (drain-fires! fn')
          (drain-fires! fn')
          (is (zero? (mends)))
          (is (= :broken (:state (sched-of seat)))))

        (testing "once the row's list names the new link, its pools mend the row through one mend and release its wake"
          (let [row (sched-of seat)]
            (store/with-tx (:storage *eng*)
              (fn [tx]
                (store/update-data! (:storage *eng*) tx :schedule (:id row)
                                    (assoc (:data row) :runners [dead fresh])
                                    (:next-flip-at row)))))
          (is (= :broken (:state (sched-of seat))) "the list was written with no door")
          (sch/sync-pools! *eng* :schedule sched-id)
          (drain-fires! fn')
          (drain-fires! fn')
          (wakes/sweep-pending! *eng*)
          (drain-fires! fn')
          (is (= 1 (mends)))
          (is (= 1 (count (fires-of new-token))) "the held wake went out through the new link")
          (is (= 1 (count (fires-of dead-token))) "the broken link was not tried again")
          (let [row (sched-of seat)]
            (is (= :live (:state row)))
            (is (not (get-in row [:data :wake_pending]))))))
      (finally
        (sch/answer! *fire* nil)
        (seat-do! seat :retire)))))

(deftest a-restored-link-mends-the-broken-schedule-its-pool-names
  ;; R-22.4 of docs/spec-seat.md: the pool's only link, retired, restored
  (let [wn :wake-mend-restored
        fn' :wake-mend-restored-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (door-seat! "mendrestoredclerk" {:fire_interval_seconds 1}
                    [{:kind "wake_task" :actions ["complete"]}]
                    fn')
        token "rk-test-mendrestored-0123456789abcdef"
        link (str (:id (:row (inv/create! *eng* :runner_link
                                          {:provider "claude_routine"
                                           :fire_url (str "https://api.anthropic.com/v1/claude_code"
                                                          "/routines/trig_mendrestored/fire")
                                           :fire_token token}
                                          {:principal elena}))))
        sched-id (str (:id (sched-of seat)))
        _ (inv/invoke! *eng* :schedule sched-id :set_runners
                       {:runners [link]} {:principal elena})
        _ (drain-fires! fn')
        mend! #(inv/invoke! *eng* :schedule sched-id :mend nil {:principal %})
        mends #(count (filter (comp #{:mend} :action) (log-of :schedule sched-id)))]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the pool's only runner refuses") :complete)
      (drain-doors!)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of token))) "the refused POST went out once")
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the broken row stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while the pool is broken") :complete)
        (doors/drain! *eng*)
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 1 (count (fires-of token))))
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

      (testing "retiring the pool's only link mends nothing"
        (inv/invoke! *eng* :runner_link link :retire nil {:principal elena})
        (drain-fires! fn')
        (drain-fires! fn')
        (is (= :fires-through-a-live-link
               (:guard (refusal #(mend! sch/system-actor))))
            "the engine's mend with only a retired link is refused")
        (is (zero? (mends)))
        (is (= :broken (:state (sched-of seat)))))

      (testing "restoring the link mends the row through one mend and releases its wake"
        (inv/invoke! *eng* :runner_link link :restore nil {:principal elena})
        (drain-fires! fn')
        (drain-fires! fn')
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 1 (mends)))
        (is (= 2 (count (fires-of token))) "the held wake went out through the restored link")
        (let [row (sched-of seat)]
          (is (= :live (:state row)))
          (is (not (get-in row [:data :wake_pending])))))
      (finally
        (sch/answer! *fire* nil)
        (seat-do! seat :retire)))))

(deftest a-pending-wake-goes-out-through-the-chairs-pool-with-no-link
  ;; waymark ticket 102d00d2: the chair model holds runners and no link
  (let [wn :wake-pool-only
        fn' :wake-pool-only-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        live-token "rk-test-poolonlylive-0123456789abcdef"
        live (str (:id (:row (inv/create! *eng* :runner_link
                                          {:provider "claude_routine"
                                           :fire_url (str "https://api.anthropic.com/v1/claude_code"
                                                          "/routines/trig_poolonlylive/fire")
                                           :fire_token live-token}
                                          {:principal elena}))))
        model (model! "pool-only-chair")
        _ (inv/invoke! *eng* :model (str model) :set_runners
                       {:runners [live]}
                       {:principal elena})
        seat (seat! "poolonlyclerk"
                    {:held_for [(str model)]
                     :wake_on [{:kind "wake_task" :actions ["complete"]}]
                     :fire_interval_seconds 1})
        _ (drain-fires! fn')]
    (try
      (is (not (sch/linked? *eng* (sched-of seat))) "neither the row nor its chair holds a link")
      (task-do! (task! "the first of two, pool only") :complete)
      (drain-wakes! wn)
      (drain-fires! fn')
      (is (= 1 (count (fires-of live-token))) "the first match fires through the pool at once")

      (task-do! (task! "the second, inside the gap") :complete)
      (drain-wakes! wn)
      (is (true? (get-in (sched-of seat) [:data :wake_pending])))

      (testing "once the gap has passed, the sweep releases the wake through the pool"
        (let [before (count (fires-of live-token))]
          (Thread/sleep 1200)
          (wakes/sweep-pending! *eng*)
          (drain-fires! fn')
          (drain-fires! fn')
          (is (= (inc before) (count (fires-of live-token))))
          (is (nil? (:text (last (fires-of live-token)))))
          (is (not (get-in (sched-of seat) [:data :wake_pending])))))
      (finally
        (seat-do! seat :retire)))))

(deftest a-pool-a-transient-refusal-left-unstarted-keeps-the-wake-pending
  ;; waymark ticket 728317e3: a 503 from the pool's one link starts no run
  (let [wn :wake-pool-transient
        fn' :wake-pool-transient-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        live-token "rk-test-pooltransient-0123456789abcdef"
        live (str (:id (:row (inv/create! *eng* :runner_link
                                          {:provider "claude_routine"
                                           :fire_url (str "https://api.anthropic.com/v1/claude_code"
                                                          "/routines/trig_pooltransient/fire")
                                           :fire_token live-token}
                                          {:principal elena}))))
        model (model! "pool-transient-chair")
        _ (inv/invoke! *eng* :model (str model) :set_runners
                       {:runners [live]}
                       {:principal elena})
        seat (seat! "transientclerk"
                    {:held_for [(str model)]
                     :fire_interval_seconds 1})
        _ (subscribe! seat {:kind "wake_task" :actions ["complete"]})
        _ (drain-fires! fn')]
    (try
      (is (not (sch/linked? *eng* (sched-of seat))) "neither the row nor its chair holds a link")
      (sch/answer! *fire* 503)
      (task-do! (task! "the one the provider cannot start") :complete)
      (drain-doors!)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of live-token))) "the refused POST went out once")
      (let [row (sched-of seat)]
        (is (= :live (:state row)) "a transient refusal is not a broken link")
        (is (true? (get-in row [:data :wake_pending])))
        (is (some? (get-in row [:data :retry_after])))
        (is (= "the routines api answered 503 for the fire"
               (get-in row [:data :note]))))

      (testing "inside the minute nothing goes out, and a new match folds in"
        (task-do! (task! "a second match, inside the minute") :complete)
        (doors/drain! *eng*)
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (= 1 (count (fires-of live-token))))
        (let [row (sched-of seat)]
          (is (= :live (:state row)))
          (is (true? (get-in row [:data :wake_pending])))
          (is (some? (get-in row [:data :retry_after])))))
      (finally
        (sch/answer! *fire* nil)
        (seat-do! seat :retire)))))

;; ── several sittings at once (max_open_sittings) ───────────────────────
;;
;; A seat of three slots runs three sittings at once, each on its own
;; row. The damper counts the runs on their way to a sit as well as the
;; open sittings, so a burst of wakes stops at the ceiling, and a
;; closing sitting fires one more run while a row is left that no open
;; sitting holds. One slot is the seat as it always was, which the
;; suite above still proves untouched.

(deftest a-seat-of-three-slots-runs-three-sittings-and-holds-the-fourth
  (let [wn :wake-slots
        fn' :wake-slots-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        batch "three-slots"
        {:keys [seat]}
        (linked-seat! "slotclerk"
                      {:scope [{:kind "wake_item" :actions ["complete" "touch"]
                                :filter {:batch batch}}]
                       :walk "wake_item"
                       :wake_on [{:kind "wake_item" :actions ["touch"]}]
                       :max_open_sittings 3}
                      fn')
        rows (vec (repeatedly 5 #(item! batch)))
        model-id (str (model! "model-for-slotclerk"))
        open! (fn [row-id]
                (let [sid (:id (:row (inv/create! *eng* :sitting
                                                  {:seat (str seat)
                                                   :model model-id
                                                   :grant (str (grant!))}
                                                  {:principal clerk})))]
                  (seats/claim-rows! *eng* sid [(str row-id)])
                  sid))]

    (testing "the seat holds the field it was given"
      (is (= 3 (get-in (raw :seat seat) [:data :max_open_sittings]))))

    (testing "three wakes in a burst fire three runs, the gap notwithstanding"
      (doseq [id (take 3 rows)] (item-do! id :touch))
      (drain-wakes! wn)
      (is (= 3 (count (seat-fires seat)))
          "a free slot for a free row is not held by the gap")
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (testing "a fourth wake is held: three runs are on their way to a sit"
      (item-do! (nth rows 3) :touch)
      (drain-wakes! wn)
      (is (= 3 (count (seat-fires seat))))
      (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

    (let [sittings (mapv open! (take 3 rows))]
      (testing "with three sittings open on three rows, the fourth still waits"
        (item-do! (nth rows 4) :touch)
        (drain-wakes! wn)
        (is (= 3 (count (seat-fires seat))))
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

      (testing "closing one sitting fires one more run, for the rows left free"
        (item-do! (first rows) :complete)
        (close-sitting! (first sittings))
        (drain-wakes! wn)
        (let [ts (seat-fires seat)]
          (is (= 4 (count ts)))
          (is (nil? (get-in (last ts) [:inputs :text]))
              "a release names no row: the sit hands the next free one"))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))
        (is (= #{(str (nth rows 1)) (str (nth rows 2))}
               (seats/claimed-rows *eng* seat nil))
            "the fourth and fifth rows are the ones no sitting holds"))

      (doseq [s (rest sittings)] (close-sitting! s)))

    (seat-do! seat :retire))

  (testing "the field defaults to one, and eleven is refused by the schema"
    (let [seat (seat! "oneslot" {})]
      (is (= 1 (get-in (raw :seat seat) [:data :max_open_sittings])))
      (seat-do! seat :retire))
    (let [p (refusal #(inv/create! *eng* :seat
                                   (seat-body "elevenslots"
                                              {:max_open_sittings 11})
                                   {:principal elena}))]
      (is (= :schema-invalid (:waymark10/problem p)))
      (is (str/includes? (pr-str (:errors p)) "max_open_sittings")))))

;; Two wakes that land back to back, with no sitting opened between
;; them, are one run when they name one row. The slots count rows and
;; not WHICH row: with a second row free, the second wake read a free
;; slot for a free row and fired a run that sat, was told its row was
;; held, and stopped (ticket 884fff20).

(deftest two-wakes-back-to-back-fire-one-run-for-one-row
  (let [wn :wake-twice
        fn' :wake-twice-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        wide-seat (fn [nm batch]
                    (:seat (linked-seat! nm
                                         {:scope [{:kind "wake_item"
                                                   :actions ["complete" "touch"]
                                                   :filter {:batch batch}}]
                                          :walk "wake_item"
                                          :wake_on [{:kind "wake_item"
                                                     :actions ["touch"]}]
                                          :max_open_sittings 8}
                                         fn')))]

    (testing "one walkable row and two wakes yield exactly one release"
      (let [batch "twice-one-row"
            seat (wide-seat "twiceoneclerk" batch)
            row (item! batch)]
        (item-do! row :touch)
        (item-do! row :touch)
        (drain-wakes! wn)
        (is (= 1 (count (seat-fires seat)))
            "the first run is in flight and the one row is its own")
        (seat-do! seat :retire)))

    (testing "a second free row does not let the same row fire twice"
      (let [batch "twice-two-rows"
            seat (wide-seat "twicetwoclerk" batch)
            [a b] (vec (repeatedly 2 #(item! batch)))]
        (item-do! a :touch)
        (item-do! a :touch)
        (drain-wakes! wn)
        (is (= 1 (count (seat-fires seat)))
            "the run in flight already names that row")
        (item-do! b :touch)
        (drain-wakes! wn)
        (is (= 2 (count (seat-fires seat)))
            "the other row's own wake still fires: a free slot for a free row")
        (seat-do! seat :retire)))

    (testing "a wake that names a row an open sitting holds fires nothing"
      (let [batch "twice-held-row"
            seat (wide-seat "twiceheldclerk" batch)
            [a b] (vec (repeatedly 2 #(item! batch)))
            s (sitting! seat)]
        (seats/claim-rows! *eng* s [(str a)])
        (item-do! a :touch)
        (drain-wakes! wn)
        (is (empty? (seat-fires seat))
            "its run would sit, be told the row is held, and stop")
        (item-do! b :touch)
        (drain-wakes! wn)
        (is (= 1 (count (seat-fires seat))))
        (close-sitting! s)
        (seat-do! seat :retire)))))

;; A sitting is born before its sit claims its row. Between the two its
;; fire is no longer a run on its way and its row is not held, so a wake
;; read the row free and fired a second run that sat to a held row
;; (ticket c822d9e4).

(deftest a-sitting-that-has-not-claimed-yet-keeps-its-row-from-a-second-fire
  (let [wn :wake-sitting-down
        fn' :wake-sitting-down-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        wide-seat (fn [nm batch]
                    (:seat (linked-seat! nm
                                         {:scope [{:kind "wake_item"
                                                   :actions ["complete" "touch"]
                                                   :filter {:batch batch}}]
                                          :walk "wake_item"
                                          :wake_on [{:kind "wake_item"
                                                     :actions ["touch"]}]
                                          :max_open_sittings 8}
                                         fn')))]

    (testing "one row, and a sitting open that has not claimed it: no fire"
      (let [batch "sitting-down-one-row"
            seat (wide-seat "sittingdownclerk" batch)
            row (item! batch)
            s (sitting! seat)]
        (is (= 1 (seats/sitting-down-count *eng* seat)))
        (item-do! row :touch)
        (drain-wakes! wn)
        (is (empty? (seat-fires seat))
            "the open sitting is about to take the one row")
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))
            "the wake is remembered, not spent")
        (seats/claim-rows! *eng* s [(str row)])
        (is (= 0 (seats/sitting-down-count *eng* seat)))
        (item-do! row :touch)
        (drain-wakes! wn)
        (is (empty? (seat-fires seat))
            "and once it is claimed the row is spoken for")
        (close-sitting! s)
        (seat-do! seat :retire)))

    (testing "a sitting whose sit handed it nothing keeps no row back"
      (let [batch "sitting-down-nothing"
            seat (wide-seat "satnothingclerk" batch)
            s (sitting! seat)
            _ (seats/stamp-walked-nothing! *eng* s true)
            row (item! batch)]
        (is (= 0 (seats/sitting-down-count *eng* seat)))
        (item-do! row :touch)
        (drain-wakes! wn)
        (is (= 1 (count (seat-fires seat)))
            "a free slot for a free row")
        (close-sitting! s)
        (seat-do! seat :retire)))))

;; A fire a pull link holds is a run in flight until a localfire takes
;; it, however long it waits; once taken, the window runs from the take;
;; once the keep sweep drops its key, the slot is free. A push fire is
;; read by its fire alone, for `in-flight-seconds` (ticket 56d104f0).

(deftest an-untaken-pull-fire-holds-its-slot-until-taken-or-dropped
  (let [seat (seat! "pullslotclerk" {:max_open_sittings 2})
        st (:storage *eng*)
        keys! (fn [entries]
                (store/with-tx st
                  (fn [tx]
                    (let [row (store/load-row st tx :seat (str seat)
                                              {:for-update true})]
                      (store/update-data! st tx :seat (str seat)
                                          (assoc (:data row) :fire_keys entries)
                                          (:next-flip-at row))))))
        now (Instant/now)
        at (.plusSeconds now 900)
        entry (fn [h extra]
                (merge {:hash h
                        :expires_at (str (.plusSeconds now 86400))
                        :fired_at (str now)}
                       extra))
        flying #(#'wakes/in-flight *eng* (str seat) at)]
    (try
      (testing "a pull fire queued untaken for 15 min still holds one slot of two"
        (keys! [(entry "pull-one" {:pull_fire "pf-1"})])
        (is (= 1 (flying))
            "one slot left, so a second wake fires at most once more"))

      (testing "a push fire's key older than the window holds no slot"
        (keys! [(entry "pull-one" {:pull_fire "pf-1"})
                (entry "push-one" {})])
        (is (= 1 (flying))))

      (testing "once taken, the window runs from the take"
        (keys! [(entry "pull-one" {:pull_fire "pf-1"
                                   :taken_at (str (.plusSeconds now 600))})])
        (is (= 1 (flying)) "taken 300s ago: still on its way to a sit")
        (keys! [(entry "pull-one" {:pull_fire "pf-1"
                                   :taken_at (str (.plusSeconds now 200))})])
        (is (= 0 (flying)) "taken 700s ago: the window has passed"))

      (testing "once the keep sweep drops its key the slot is free"
        (keys! [(entry "pull-one" {:pull_fire "pf-1"})])
        (is (= 1 (flying)))
        (seats/revoke-fire-key! *eng* {:seat (str seat) :hash "pull-one"})
        (is (= 0 (flying))))
      (finally
        (seat-do! seat :retire)))))

;; A sit that found every row held is stamped `walked_nothing` and stays
;; open until the hook closes it with its spend. It holds no slot while
;; it waits (ticket cc2f8e7f).

(deftest a-sitting-that-walked-nothing-holds-no-slot
  (let [wn :wake-walked-nothing-slot
        fn' :wake-walked-nothing-slot-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        batch "walked-nothing-slot"
        seat (:seat (linked-seat! "nothingslotclerk"
                                  {:scope [{:kind "wake_item"
                                            :actions ["complete" "touch"]
                                            :filter {:batch batch}}]
                                   :walk "wake_item"
                                   :wake_on [{:kind "wake_item"
                                              :actions ["touch"]}]
                                   :max_open_sittings 2}
                                  fn'))
        model-id (str (model! "model-for-nothingslotclerk"))
        open! (fn []
                (:id (:row (inv/create! *eng* :sitting
                                        {:seat (str seat)
                                         :model model-id
                                         :grant (str (grant!))}
                                        {:principal clerk}))))
        held (item! batch)
        s1 (open!)
        _ (seats/claim-rows! *eng* s1 [(str held)])
        s2 (open!)
        _ (seats/stamp-walked-nothing! *eng* s2 true)
        row (item! batch)]
    (is (= 2 (seats/open-sitting-count *eng* seat)))
    (is (= 1 (seats/busy-sitting-count *eng* seat))
        "the sitting that walked nothing is open and not busy")
    (item-do! row :touch)
    (drain-wakes! wn)
    (is (= 1 (count (seat-fires seat)))
        "its slot is free for the row that came free")
    (close-sitting! s2)
    (close-sitting! s1)
    (seat-do! seat :retire)))

(deftest a-count-wake-counts-only-the-rows-no-open-sitting-holds
  (let [wn :wake-held-rows
        fn' :wake-held-rows-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        held-seat (fn [nm batch]
                    (:seat (linked-seat! nm
                                         {:scope [{:kind "wake_item"
                                                   :actions ["complete" "touch"]
                                                   :filter {:batch batch}}]
                                          :walk "wake_item"
                                          :wake_on [{:kind "wake_item"
                                                     :actions ["touch"]
                                                     :filter {:batch batch}
                                                     :at_least 1}]
                                          :max_open_sittings 3}
                                         fn')))
        hold! (fn [seat row-id]
                (let [sid (sitting! seat)]
                  (seats/claim-rows! *eng* sid [(str row-id)])
                  sid))]

    (testing "a seat whose one open row an open sitting holds is not woken"
      (let [row (item! "held-only")
            seat (held-seat "heldonlyclerk" "held-only")
            s (hold! seat row)]
        (item-do! row :touch)
        (drain-wakes! wn)
        (is (empty? (seat-fires seat)))
        (is (not (get-in (sched-of seat) [:data :wake_pending]))
            "the count read the walk the sit would hand, and it was empty")
        (close-sitting! s)
        (seat-do! seat :retire)))

    (testing "a seat with two open rows, one held, is woken for the other"
      (let [held (item! "held-one-of-two")
            _free (item! "held-one-of-two")
            seat (held-seat "heldoneclerk" "held-one-of-two")
            s (hold! seat held)]
        (item-do! held :touch)
        (drain-wakes! wn)
        (is (= 1 (count (seat-fires seat))))
        (close-sitting! s)
        (seat-do! seat :retire)))))

;; A release sends one run for each waiting text and one for the wake of
;; no text. Three of them over two free rows started a third run that
;; sat to a walk its siblings had taken (ticket 8c071e14). A seat of
;; several slots now starts no more runs than it has free rows.

(deftest a-release-starts-no-more-runs-than-the-seat-has-free-rows
  (let [fn' :wake-free-rows-fires
        _ (drain-fires! fn')
        wide-seat (fn [nm batch]
                    (:seat (linked-seat! nm
                                         {:scope [{:kind "wake_item"
                                                   :actions ["complete" "touch"]
                                                   :filter {:batch batch}}]
                                          :walk "wake_item"
                                          :max_open_sittings 3}
                                         fn')))
        waiting! (fn [seat texts]
                   (let [row (sched-of seat)]
                     (store/with-tx (:storage *eng*)
                       (fn [tx]
                         (store/update-data! (:storage *eng*) tx :schedule (:id row)
                                             (assoc (:data row)
                                                    :wake_pending true
                                                    :wake_texts texts
                                                    :wake_text (first texts)
                                                    :wake_textless true)
                                             (:next-flip-at row))))))]

    (testing "three slots and two walkable rows start two runs"
      (let [batch "free-rows-two"
            seat (wide-seat "freerowstwo" batch)
            [a b] (vec (repeatedly 2 #(item! batch)))
            texts [(str "Walk " a " again.") (str "Walk " b " again.")]]
        (waiting! seat texts)
        (wakes/tick! *eng*)
        (is (= texts (mapv #(get-in % [:inputs :text]) (seat-fires seat)))
            "two rows, two runs: the wake of no text has no row left")
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))
            "and it waits for a row rather than being spent")
        (is (empty? (sch/waiting-texts (:data (sched-of seat)))))
        (wakes/tick! *eng*)
        (is (= 2 (count (seat-fires seat)))
            "the two runs on their way have both rows")
        (seat-do! seat :retire)))

    (testing "with every row held by an open sitting, none starts"
      (let [batch "free-rows-none"
            seat (wide-seat "freerowsnone" batch)
            rows (vec (repeatedly 2 #(item! batch)))
            model-id (str (model! "model-for-freerowsnone"))
            sittings (mapv (fn [row]
                             (let [s (:id (:row (inv/create! *eng* :sitting
                                                             {:seat (str seat)
                                                              :model model-id
                                                              :grant (str (grant!))}
                                                             {:principal clerk})))]
                               (seats/claim-rows! *eng* s [(str row)])
                               s))
                           rows)]
        (waiting! seat [(str "Walk " (first rows) " again.")])
        (wakes/tick! *eng*)
        (is (empty? (seat-fires seat)))
        (doseq [s sittings] (close-sitting! s))
        (seat-do! seat :retire)))

    ;; the slots count rows and not WHICH row: a text that waited names
    ;; a row a sitting took since, and the second row is free (ticket
    ;; d65f9247)
    (testing "a waiting text on a row an open sitting holds is not fired"
      (let [batch "free-rows-held"
            seat (wide-seat "freerowsheld" batch)
            [a b] (vec (repeatedly 2 #(item! batch)))
            s (sitting! seat)
            text-b (str "Walk " b " again.")]
        (seats/claim-rows! *eng* s [(str a)])
        (waiting! seat [(str "Walk " a " again.") text-b])
        (wakes/tick! *eng*)
        (is (= [text-b] (mapv #(get-in % [:inputs :text]) (seat-fires seat)))
            "the free row's text goes out, and the held row's does not")
        (is (empty? (sch/waiting-texts (:data (sched-of seat))))
            "the held row's text is dropped: its run answers for it")
        (close-sitting! s)
        (seat-do! seat :retire)))

    ;; each text is judged before this release fires any, so the second
    ;; text on a row does not see the first's fire (ticket 2be02e40)
    (testing "two waiting texts of different wording on one row start one run"
      (let [batch "free-rows-twice"
            seat (wide-seat "freerowstwice" batch)
            [a _b] (vec (repeatedly 2 #(item! batch)))
            first-text (str "Walk " a " again.")
            row (sched-of seat)]
        (store/with-tx (:storage *eng*)
          (fn [tx]
            (store/update-data! (:storage *eng*) tx :schedule (:id row)
                                (assoc (:data row)
                                       :wake_pending true
                                       :wake_texts [first-text
                                                    (str "Look at " a " once more.")]
                                       :wake_text first-text
                                       :wake_textless false)
                                (:next-flip-at row))))
        (wakes/tick! *eng*)
        (is (= [first-text] (mapv #(get-in % [:inputs :text]) (seat-fires seat)))
            "one row named, one run: the first text's, with a second row free")
        (is (empty? (sch/waiting-texts (:data (sched-of seat))))
            "the second text is dropped: the first's run answers for the row")
        (seat-do! seat :retire)))

    ;; a seat of one slot sends each waiting text, and two of them on
    ;; one row were two runs for it (ticket fd179d32)
    (testing "a seat of one slot starts one run for two texts on one row"
      (let [batch "free-rows-one-slot"
            seat (:seat (linked-seat! "freerowsoneslot"
                                      {:scope [{:kind "wake_item"
                                                :actions ["complete" "touch"]
                                                :filter {:batch batch}}]
                                       :walk "wake_item"}
                                      fn'))
            [a b] (vec (repeatedly 2 #(item! batch)))
            first-text (str "Walk " a " again.")
            other-text (str "Walk " b " again.")
            row (sched-of seat)]
        (store/with-tx (:storage *eng*)
          (fn [tx]
            (store/update-data! (:storage *eng*) tx :schedule (:id row)
                                (assoc (:data row)
                                       :wake_pending true
                                       :wake_texts [first-text
                                                    (str "Look at " a " once more.")
                                                    other-text]
                                       :wake_text first-text
                                       :wake_textless false)
                                (:next-flip-at row))))
        (wakes/tick! *eng*)
        (is (= [first-text other-text]
               (mapv #(get-in % [:inputs :text]) (seat-fires seat)))
            "one run for each row named: the second text on the first row is dropped")
        (is (empty? (sch/waiting-texts (:data (sched-of seat)))))
        (seat-do! seat :retire)))))

;; ── a walkthrough's author is woken on its turn ─────────────────────
;;
;; docs/spec-walkthrough.md § 3, "How the author learns". A
;; `walkthrough` is an ordinary kind, and `author` and `waiting_on` are
;; filterable, so the wake has no code of its own for it. What these
;; cases prove is that the ONE entry the spec writes does what the spec
;; says, because the filter is read after the transition committed.

(defn- leader-of
  "The principal a sitting of this seat acts as. A walkthrough it
  creates carries this id as its `author`."
  [seat-id]
  (t/principal {:id (str "seat:" seat-id) :type :agent :display "Leader"}))

(def ^:private leader-grant
  "The guard's-eye view of a grant that sees every row and admits
  every door. The create guards judge the author's grant, and they are
  not what these cases are about (`waymark10.walkthroughs-test`)."
  {:id "grant-leader"
   :action? (fn [_kind _action] true)
   :row? (fn [_kind _id] true)})

(defn- write-wake-on!
  "The seat's wake entries, each as a door subscription on its fire
  door, its owner's own (`seats/wake-subscription`): these entries name
  the seat's id, which only the create answers."
  [seat-id entries]
  (let [row (raw :seat seat-id)]
    (doseq [e entries]
      (inv/create! *eng* :subscription (seats/wake-subscription row e)
                   {:principal seats/seats-actor}))))

(defn- leading-seat!
  "A linked seat that leads walkthroughs: its one wake entry is the
  spec's own. The entry names the seat's id, and only the create
  answers that id, so its subscription is written before the link.
  → {:seat id :token token}."
  [nm fire-cursor]
  (let [seat-id (seat! nm {})
        token (str "rk-test-" nm "-0123456789abcdef")]
    (write-wake-on! seat-id
                    [{:kind "walkthrough"
                      :actions ["start" "step" "resume" "stop" "finish"]
                      :filter {:author (str "seat:" seat-id)
                               :waiting_on "agent"}}])
    (drain-fires! fire-cursor)
    (inv/invoke! *eng* :schedule (str (:id (sched-of seat-id))) :link
                 {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                 "/routines/trig_" nm "/fire")
                  :token token}
                 {:principal elena})
    {:seat seat-id :token token}))

(def ^:private an-agent-step
  {:who "agent" :note "Watch me: reading the open forms."})

(defn- a-person-step [form-id]
  {:who "person"
   :self (str "/api/wake_forms/" form-id)
   :action "rename"
   :fields ["title"]
   :note "Give the form its new name."})

(defn- form! [title]
  (:id (:row (inv/create! *eng* :wake_form {:title title}
                          {:principal elena}))))

(defn- lead!
  "The seat's sitter offers `steps` to Elena. → the walkthrough's id."
  [seat-id steps]
  (:id (:row (inv/create! *eng* :walkthrough
                          {:subject "elena"
                           :title "Filing the gas bill"
                           :steps steps}
                          {:principal (leader-of seat-id)
                           :grant leader-grant}))))

(defn- led-do! [id principal action]
  (inv/invoke! *eng* :walkthrough (str id) action {}
               {:principal principal
                :idempotency-key (str (random-uuid))}))

(defn- waiting-on [id]
  (some-> (get-in (raw :walkthrough id) [:data :waiting_on]) name))

(defn- settle-led!
  "Drain the invitations consumer and the walkthroughs consumer, each
  under this deftest's own cursor, until neither hears anything. The
  chain from a person's transition to the engine's `step` is several
  log entries, each heard from the log."
  [invitations-cursor walkthroughs-cursor]
  (loop [left 20]
    (let [n (+ (consumers/drain-consumer! *eng* invitations-cursor
                                          (invitations/consumer-fn *eng*))
               (consumers/drain-consumer! *eng* walkthroughs-cursor
                                          (walkthroughs/consumer-fn *eng*)))]
      (when (and (pos? n) (pos? left))
        (recur (dec left))))))

(deftest a-seat-is-woken-when-its-walkthrough-waits-on-it
  (let [wn :wake-led
        fn' :wake-led-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]} (leading-seat! "leadclerk" fn')
        id (lead! seat [an-agent-step])]
    (drain-wakes! wn)

    (testing "an offer wakes nobody: the person has not agreed to be led"
      (is (empty? (seat-fires seat)))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (led-do! id elena :start)
    (drain-wakes! wn)

    (testing "the start opens the agent's step, and the seat fires once
              with the walkthrough named"
      (is (= "agent" (waiting-on id)))
      (let [ts (seat-fires seat)]
        (is (= 1 (count ts)))
        (let [text (str (get-in (first ts) [:inputs :text]))]
          (is (str/includes? text (str id)))
          (is (str/includes? text "walkthrough"))
          (is (str/includes? text "start"))
          (is (str/includes? text "running")))))

    (testing "and exactly one POST reached this seat's Routine"
      (drain-fires! fn')
      (is (= 1 (count (fires-of token))))
      (is (str/includes? (str (:text (last (fires-of token)))) (str id))))

    (seat-do! seat :retire)))

(deftest a-person-step-opening-does-not-wake-the-author
  (let [wn :wake-led-person
        fn' :wake-led-person-fires
        in :wake-led-person-invitations
        wt :wake-led-person-walkthroughs
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        _ (settle-led! in wt)
        {:keys [seat token]} (leading-seat! "leadpersonclerk" fn')
        form (form! "The gas bill")
        id (lead! seat [(a-person-step form) an-agent-step])]
    (led-do! id elena :start)
    (settle-led! in wt)
    (drain-wakes! wn)

    (testing "the person's step opened, and nobody was woken for it"
      (is (= "person" (waiting-on id)))
      (is (empty? (seat-fires seat)))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))
          "nothing is remembered either: the row is not under the filter")
      (drain-fires! fn')
      (is (empty? (fires-of token))))

    (inv/invoke! *eng* :wake_form (str form) :rename {:title "The water bill"}
                 {:principal elena})
    (settle-led! in wt)
    (drain-wakes! wn)

    (testing "the person's answer ends their step, the engine's `step`
              opens the agent's, and that one wakes the seat"
      (is (= 2 (get-in (raw :walkthrough id) [:data :current])))
      (is (= "agent" (waiting-on id)))
      (let [ts (seat-fires seat)]
        (is (= 1 (count ts)))
        (let [text (str (get-in (first ts) [:inputs :text]))]
          (is (str/includes? text (str id)))
          (is (str/includes? text "walkthrough"))
          (is (str/includes? text "step"))))
      (drain-fires! fn')
      (is (= 1 (count (fires-of token)))))

    (seat-do! seat :retire)))

(deftest a-damped-wake-leaves-the-walkthrough-in-the-seats-queue
  (let [wn :wake-led-damped
        fn' :wake-led-damped-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]} (leading-seat! "leaddampedclerk" fn')
        ;; the agent's queue, as docs/spec-walkthrough.md § 1 writes it
        queue {:author (str "seat:" seat) :state "running" :waiting_on "agent"}
        open-one (sitting! seat)
        id (lead! seat [an-agent-step])]
    (led-do! id elena :start)
    (drain-wakes! wn)

    (testing "the open sitting stops the fire, and the match waits"
      (is (empty? (seat-fires seat)))
      (drain-fires! fn')
      (is (empty? (fires-of token)))
      (is (true? (get-in (sched-of seat) [:data :wake_pending]))))

    (testing "the close releases exactly one fire, and it names NO row"
      (close-sitting! open-one)
      (drain-wakes! wn)
      (let [ts (seat-fires seat)]
        (is (= 1 (count ts)))
        (is (nil? (get-in (first ts) [:inputs :text]))))
      (drain-fires! fn')
      (is (= 1 (count (fires-of token))))
      (is (nil? (:text (last (fires-of token))))))

    (testing "the walkthrough the fire did not name is the one row of the
              seat's queue, so the session that walks the queue finds it"
      (is (= 1 (wakes/count-under *eng* :walkthrough queue)))
      (is (true? (wakes/moved-under? *eng* :walkthrough id queue))))

    (seat-do! seat :retire)))

;; The same seat can name `walkthrough` as its `walk`. The walk's filter
;; is its scope entry's, and that filter names the seat's own id, so the
;; `restate` that writes the wake entry writes the scope and the walk
;; with it. Then the queue of the case above is the seat's walk: the
;; count the sit's page is taken from holds the waiting row, and a
;; release into an empty queue fires nothing.

(defn- walking-leader!
  "A leading seat whose `walk` is `walkthrough`, under the queue's own
  filter in its scope entry. → {:seat id :token token}."
  [nm fire-cursor]
  (let [seat-id (seat! nm {})
        token (str "rk-test-" nm "-0123456789abcdef")
        author (str "seat:" seat-id)]
    (restate! seat-id
              {:walk "walkthrough"
               :scope [{:kind "walkthrough"
                        :actions ["create" "advance" "stop" "resume"
                                  "withdraw"]
                        :filter {:author author
                                 :state "running"
                                 :waiting_on "agent"}}]})
    (write-wake-on! seat-id
                    [{:kind "walkthrough"
                      :actions ["start" "step" "resume" "stop" "finish"]
                      :filter {:author author
                               :waiting_on "agent"}}])
    (drain-fires! fire-cursor)
    (inv/invoke! *eng* :schedule (str (:id (sched-of seat-id))) :link
                 {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                 "/routines/trig_" nm "/fire")
                  :token token}
                 {:principal elena})
    {:seat seat-id :token token}))

(deftest a-leading-seat-that-walks-walkthrough-is-handed-its-waiting-row
  (let [wn :wake-led-walk
        fn' :wake-led-walk-fires
        in :wake-led-walk-invitations
        wt :wake-led-walk-walkthroughs
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        _ (settle-led! in wt)]

    (testing "a damped wake leaves the row in the seat's walk"
      (let [{:keys [seat token]} (walking-leader! "leadwalkclerk" fn')
            seat-row #(raw :seat seat)
            open-one (sitting! seat)
            id (lead! seat [an-agent-step])]
        (is (= "walkthrough" (str (get-in (seat-row) [:data :walk])))
            "the restate wrote the walk")
        (is (= {"author" (str "seat:" seat)
                "state" "running"
                "waiting_on" "agent"}
               (into {} (map (fn [[k v]] [(name k) (str v)]))
                     (seats/walk-filter (seat-row))))
            "and the walk's filter is the scope entry's")
        (is (= 0 (#'wakes/walk-count *eng* (seat-row)))
            "a walkthrough nobody started is not in the walk")

        (led-do! id elena :start)
        (drain-wakes! wn)
        (is (empty? (seat-fires seat)))
        (is (true? (get-in (sched-of seat) [:data :wake_pending])))

        (close-sitting! open-one)
        (drain-wakes! wn)
        (let [ts (seat-fires seat)]
          (is (= 1 (count ts)) "the close releases one fire")
          (is (nil? (get-in (first ts) [:inputs :text]))
              "and it names no row"))
        (drain-fires! fn')
        (is (= 1 (count (fires-of token))))

        (is (= 1 (#'wakes/walk-count *eng* (seat-row)))
            "the sit's page is counted from this: the waiting row is on it")
        (is (false? (#'wakes/empty-walk? *eng* (seat-row))))
        (seat-do! seat :retire)))

    (testing "a release into an empty walk fires nothing"
      (let [{:keys [seat token]} (walking-leader! "leadwalkemptyclerk" fn')
            seat-row #(raw :seat seat)
            open-one (sitting! seat)
            id (lead! seat [an-agent-step])]
        (led-do! id elena :start)
        (drain-wakes! wn)
        (is (true? (get-in (sched-of seat) [:data :wake_pending]))
            "the start was damped by the open sitting")

        ;; the sitting does the agent step, and the row leaves the walk
        (led-do! id (leader-of seat) :advance)
        (settle-led! in wt)
        (drain-wakes! wn)
        (is (not= "agent" (waiting-on id)))
        (is (= 0 (#'wakes/walk-count *eng* (seat-row))))
        (is (true? (#'wakes/empty-walk? *eng* (seat-row))))

        (close-sitting! open-one)
        (drain-wakes! wn)
        (is (empty? (seat-fires seat)))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))
        (wakes/sweep-pending! *eng*)
        (drain-fires! fn')
        (is (empty? (seat-fires seat)) "nor does the tick fire it later")
        (is (empty? (fires-of token)))
        (seat-do! seat :retire)))))

;; ── a row another seat's open sitting moved into the walk ───────────
;;
;; Ticket 2885ad6e. A seat that grooms drafts and a seat that works the
;; groomed rows walk ONE kind under two filters. The groomer's sitting
;; was handed the draft, and its door moved the row into the worker's
;; walk while that sitting was still open. The hold of another seat's
;; open sitting (ticket 40d8f04c) outlived the move, so the worker's
;; walk was counted without the row, and the wake the move earned read
;; `empty_walk` and was dropped.

(deftest a-row-another-seats-open-sitting-moved-into-the-walk-fires
  (let [wn :wake-groomed-in
        fn' :wake-groomed-in-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        groomer (seat! "memogroomer"
                       {:walk "wake_memo"
                        :scope [{:kind "wake_memo" :actions ["send"]
                                 :filter {:state "draft"}}]})
        {:keys [seat token]}
        (linked-seat! "memoworker"
                      {:walk "wake_memo"
                       :scope [{:kind "wake_memo" :actions ["recall"]
                                :filter {:state "sent"}}]}
                      fn')
        seat-row #(raw :seat seat)
        held? #(contains? (seats/unwalkable-rows *eng* (seat-row) nil) (str %))
        memo-id (memo! "a memo the groomer sends")
        grooming (sitting! groomer)
        _ (seats/claim-rows! *eng* grooming [(str memo-id)])]

    (testing "a draft in the groomer's hands is held from the worker"
      (drain-wakes! wn)
      (is (empty? (seat-fires seat)))
      (is (held? memo-id)))

    (testing "the send ends the hold, though the groomer's sitting is open"
      (memo-do! memo-id :send)
      (is (not (held? memo-id)))
      (is (pos? (long (#'wakes/walk-count *eng* (seat-row)))))
      (is (false? (#'wakes/empty-walk? *eng* (seat-row)))))

    (testing "and the wake the send earned fires, naming the row"
      (drain-wakes! wn)
      (is (= 1 (count (seat-fires seat))))
      (drain-fires! fn')
      (is (= 1 (count (fires-of token))))
      (is (str/includes? (str (:text (last (fires-of token)))) (str memo-id))))

    (testing "the worker's sit takes the row the count counted"
      (let [working (sitting! seat)]
        (is (true? (:claimed? (seats/claim-rows-atomically!
                               *eng* seat working [(str memo-id)]))))
        (close-sitting! working)))

    (close-sitting! grooming)
    (seat-do! seat :retire)
    (seat-do! groomer :retire)))

;; Ticket cbe4d495. org-director was woken for a plan that the sitting
;; of another seat still held: its run had ended, but the sitting stayed
;; open until the idle sweep, and the wake read `empty_walk` and was
;; spent. That hold now lapses one release grace after the sitting's
;; last call, and the wake waits for the lapse, with its text.

(defn- called-at!
  "Write what a sit and its calls leave on an open sitting: a lease on
  each row it was handed and the moment of its last call."
  [sitting-id row-ids ^Instant at]
  (let [st (:storage *eng*)
        row (raw :sitting sitting-id)
        seat (raw :seat (str (get-in row [:data :seat])))
        leased (seats/held-row-ids *eng* sitting-id)]
    (store/with-tx st
      (fn [tx]
        (leased! tx row seat (remove (set leased) (map str row-ids)) nil)
        (store/update-data! st tx :sitting (str sitting-id)
                            (assoc (:data row) :last_call_at (str at))
                            (:next-flip-at row))))))

(deftest a-wake-another-seats-idle-sitting-emptied-fires-when-the-hold-lapses
  (let [wn :wake-held-elsewhere
        fn' :wake-held-elsewhere-fires
        batch "held-elsewhere"
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [scope [{:kind "wake_item" :actions ["complete"]
                    :filter {:batch batch}}]
            holder (seat! "heldholder" {:walk "wake_item" :scope scope})
            {:keys [seat]}
            (linked-seat! "helddirector"
                          {:walk "wake_item"
                           :scope scope
                           :wake_on [{:kind "wake_item" :actions ["create"]
                                      :filter {:batch batch}}]}
                          fn')
            held? #(contains? (seats/unwalkable-rows *eng* (raw :seat seat) nil)
                              (str %))
            holding (sitting! holder)
            item (item! batch)]
        (called-at! holding [item] t0)

        (testing "the create fires nothing while the other seat's run is
                  live, and the wake is due when its hold lapses"
          (drain-wakes! wn)
          (is (held? item))
          (is (empty? (seat-fires seat)))
          (is (true? (get-in (sched-of seat) [:data :wake_pending]))
              "the wake is remembered, not spent")
          (is (= (.plusSeconds t0 120) (due-of seat))))

        (testing "inside the grace the tick still fires nothing"
          (at 60)
          (wakes/tick! *eng*)
          (is (empty? (seat-fires seat))))

        (testing "past the grace the idle sitting, still open, yields the
                  row, and one fire goes out naming it"
          (at 121)
          (is (= :open (:state (raw :sitting holding))))
          (is (not (held? item)))
          (wakes/tick! *eng*)
          (let [ts (seat-fires seat)]
            (is (= 1 (count ts)))
            (is (str/includes? (str (get-in (first ts) [:inputs :text]))
                               (str item))))
          (is (not (get-in (sched-of seat) [:data :wake_pending]))))

        (testing "a holder that calls again holds again, and its close
                  ends the hold"
          (called-at! holding [item] @clock)
          (is (held? item))
          (close-sitting! holding)
          (is (not (held? item))))

        (seat-do! seat :retire)
        (seat-do! holder :retire)))))

;; Ticket bf17ce48. A run fired by hand that walked nothing because
;; another seat's sitting held its row was not woken when the hold
;; ended: the reason was only words on its sitting. The sit now marks
;; the schedule pending, due when the hold lifts, naming the row.

(deftest a-sit-another-seats-sitting-emptied-wakes-its-seat-when-the-hold-ends
  (let [wn :wake-sat-held-elsewhere
        fn' :wake-sat-held-elsewhere-fires
        batch "sat-held-elsewhere"
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        ^Instant t0 (Instant/now)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [scope [{:kind "wake_item" :actions ["complete"]
                    :filter {:batch batch}}]
            holder (seat! "satholder" {:walk "wake_item" :scope scope})
            {:keys [seat]}
            (linked-seat! "satfired"
                          {:walk "wake_item"
                           :scope scope
                           :wake_on [{:kind "wake_item" :actions ["complete"]
                                      :filter {:batch batch}}]}
                          fn')
            holding (sitting! holder)
            item (item! batch)
            text (wire/write-json {:kind "wake_item" :id (str item)})
            defer! #(sch/defer-until-elsewhere-lifts!
                     *eng* (raw :seat seat) #{(str item)} text)]
        (drain-wakes! wn)

        (testing "a row no other seat holds defers nothing"
          (is (nil? (defer!)))
          (is (not (get-in (sched-of seat) [:data :wake_pending]))))

        (called-at! holding [item] t0)

        (testing "held by the other seat's sitting, the schedule waits for
                  the hold to lapse, with the row's text"
          (is (= (.plusSeconds t0 120) (defer!)))
          (is (true? (get-in (sched-of seat) [:data :wake_pending])))
          (is (= (.plusSeconds t0 120) (due-of seat)))
          (is (= [text] (sch/waiting-texts (:data (sched-of seat)))))
          (is (empty? (seat-fires seat))))

        (testing "the holder's sitting closes, and the tick fires the seat
                  naming the row"
          (close-sitting! holding)
          (at 121)
          (wakes/tick! *eng*)
          (let [ts (seat-fires seat)]
            (is (= 1 (count ts)))
            (is (str/includes? (str (get-in (first ts) [:inputs :text]))
                               (str item))))
          (is (not (get-in (sched-of seat) [:data :wake_pending]))))

        (seat-do! seat :retire)
        (seat-do! holder :retire)))))

;; Ticket 7c4f0392. ops-seat was woken by a burst of fourteen creates
;; and handed ten, its `rows_per_firing`. No create came after, so the
;; four it was not handed waited for the silent crack. The close of a
;; sitting that was handed a full batch now fires the seat again.

(deftest a-seat-whose-firing-hit-rows-per-firing-is-fired-again
  (let [wn :wake-refire
        fn' :wake-refire-fires
        batch "refire-burst"
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (linked-seat! "refireclerk"
                      {:walk "wake_item"
                       :scope [{:kind "wake_item" :actions ["complete"]
                                :filter {:batch batch}}]
                       :wake_on [{:kind "wake_item" :actions ["create"]
                                  :filter {:batch batch}}]
                       :rows_per_firing 10
                       :fire_interval_seconds 1}
                      fn')
        waiting (nth-sitting! seat 1)
        ids (vec (repeatedly 14 #(item! batch)))
        [first-ten last-four] (split-at 10 ids)]
    (drain-wakes! wn)
    (Thread/sleep 1200)
    (close-sitting! waiting)
    (drain-wakes! wn)

    (testing "the burst waited on the open sitting and fired once"
      (is (= 1 (count (seat-fires seat)))))

    (testing "a sitting handed ten of fourteen fires the seat again at
              its close, for the queue"
      (let [run (nth-sitting! seat 2)]
        (is (true? (:claimed? (seats/claim-rows-atomically!
                               *eng* seat run (mapv str first-ten)))))
        (doseq [id first-ten] (item-do! id :complete))
        (drain-wakes! wn)
        (Thread/sleep 1200)
        (close-sitting! run)
        (drain-wakes! wn)
        (let [ts (seat-fires seat)]
          (is (= 2 (count ts)))
          (is (nil? (get-in (last ts) [:inputs :text]))))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))))

    (testing "the second run is handed the four, and its close, with the
              walk empty, fires nothing"
      (let [run (nth-sitting! seat 3)]
        (is (true? (:claimed? (seats/claim-rows-atomically!
                               *eng* seat run (mapv str last-four)))))
        (doseq [id last-four] (item-do! id :complete))
        (drain-wakes! wn)
        (Thread/sleep 1200)
        (close-sitting! run)
        (drain-wakes! wn)
        (is (= 2 (count (seat-fires seat))))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))))

    (seat-do! seat :retire)))

(deftest a-full-batch-whose-walk-empties-is-not-fired-again
  (let [wn :wake-refire-empty
        fn' :wake-refire-empty-fires
        batch "refire-exact"
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (linked-seat! "refireemptyclerk"
                      {:walk "wake_item"
                       :scope [{:kind "wake_item" :actions ["complete"]
                                :filter {:batch batch}}]
                       :wake_on [{:kind "wake_item" :actions ["create"]
                                  :filter {:batch batch}}]
                       :rows_per_firing 10
                       :fire_interval_seconds 1}
                      fn')
        waiting (nth-sitting! seat 1)
        ids (vec (repeatedly 10 #(item! batch)))]
    (drain-wakes! wn)
    (Thread/sleep 1200)
    (close-sitting! waiting)
    (drain-wakes! wn)
    (is (= 1 (count (seat-fires seat))))
    (let [run (nth-sitting! seat 2)]
      (is (true? (:claimed? (seats/claim-rows-atomically!
                             *eng* seat run (mapv str ids)))))
      (doseq [id ids] (item-do! id :complete))
      (drain-wakes! wn)
      (Thread/sleep 1200)
      (close-sitting! run)
      (drain-wakes! wn)
      (is (= 1 (count (seat-fires seat))))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))
    (seat-do! seat :retire)))

;; Ticket 629f81b9. ops-seat's fires were create wakes, each naming its
;; one new ci_run, so each sit handed that row alone while four older
;; red rows stayed in the walk, counted by the silent crack and never
;; handed. The close of a sitting handed fewer than its cap now fires
;; the seat again when its walk holds rows that sitting was not handed.

(deftest a-named-firing-that-leaves-older-rows-is-fired-again
  (let [wn :wake-refire-named
        fn' :wake-refire-named-fires
        batch "refire-named"
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (linked-seat! "refirenamedclerk"
                      {:walk "wake_item"
                       :scope [{:kind "wake_item" :actions ["complete"]
                                :filter {:batch batch}}]
                       :wake_on [{:kind "wake_item" :actions ["create"]
                                  :filter {:batch batch}}]
                       :rows_per_firing 10
                       :fire_interval_seconds 1}
                      fn')
        waiting (nth-sitting! seat 1)
        ids (vec (repeatedly 5 #(item! batch)))
        [older newest] [(vec (butlast ids)) (last ids)]]
    (drain-wakes! wn)
    (Thread/sleep 1200)
    (close-sitting! waiting)
    (drain-wakes! wn)
    (is (= 1 (count (seat-fires seat))))

    (testing "a sitting handed only the row its fire named fires the seat
              again at its close, for the four older rows it withheld"
      (let [run (nth-sitting! seat 2)]
        (is (true? (:claimed? (seats/claim-rows-atomically!
                               *eng* seat run [(str newest)]))))
        (item-do! newest :complete)
        (drain-wakes! wn)
        (Thread/sleep 1200)
        (close-sitting! run)
        (drain-wakes! wn)
        (let [ts (seat-fires seat)]
          (is (= 2 (count ts)))
          (is (nil? (get-in (last ts) [:inputs :text]))))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))))

    (testing "the next run is handed the four; a row it leaves does not
              fire the seat again"
      (let [run (nth-sitting! seat 3)]
        (is (true? (:claimed? (seats/claim-rows-atomically!
                               *eng* seat run (mapv str older)))))
        (doseq [id (rest older)] (item-do! id :complete))
        (drain-wakes! wn)
        (Thread/sleep 1200)
        (close-sitting! run)
        (drain-wakes! wn)
        (is (= 2 (count (seat-fires seat))))
        (is (not (get-in (sched-of seat) [:data :wake_pending])))))

    (seat-do! seat :retire)))

;; Ticket 00bad58a. A fired run that walked its row and left without the
;; delivery its seat owes is woken once more for that row. A second miss
;; on the same row records `undelivered` on the seat and fires nothing,
;; and a delivered run in between starts the count again.

(defn- undelivered-of [seat-id]
  (filterv #(= :undelivered (:action %)) (log-of :seat seat-id)))

(defn- delivered-close!
  "`close-sitting!`, with a delivered sitting's outcome written over the
  close's own before the wake consumer reads it."
  [sitting-id]
  (close-sitting! sitting-id)
  (let [st (:storage *eng*)]
    (store/with-tx st
      (fn [tx]
        (let [row (store/load-row st tx :sitting (str sitting-id) {})]
          (store/update-data! st tx :sitting (str sitting-id)
                              (assoc (:data row) :outcome "submitted")
                              nil))))))

(deftest a-walked-row-left-undelivered-wakes-once-then-is-recorded
  (let [wn :wake-undelivered
        fn' :wake-undelivered-fires
        batch "undelivered"
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (linked-seat! "undeliveredclerk"
                      {:walk "wake_item"
                       :scope [{:kind "wake_item" :actions ["complete"]
                                :filter {:batch batch}}]
                       :wake_on [{:kind "wake_item" :actions ["create"]
                                  :filter {:batch batch}}]
                       :delivers [{:kind "wake_item" :action "complete"}]
                       :release_grace_seconds 0
                       :rows_per_firing 10
                       :fire_interval_seconds 1}
                      fn')
        waiting (nth-sitting! seat 1)
        id (str (item! batch))
        walk! (fn [n close!]
                (let [run (nth-sitting! seat n)]
                  (is (true? (:claimed? (seats/claim-rows-atomically!
                                         *eng* seat run [id]))))
                  (drain-wakes! wn)
                  (Thread/sleep 1200)
                  (close! run)
                  (drain-wakes! wn)))
        names-row? (fn [n]
                     (str/includes?
                      (str (get-in (nth (seat-fires seat) n) [:inputs :text]))
                      id))]
    (drain-wakes! wn)
    (Thread/sleep 1200)
    (close-sitting! waiting)
    (drain-wakes! wn)
    (is (= 1 (count (seat-fires seat))))

    (testing "the first miss wakes the seat once, naming the row"
      (walk! 2 close-sitting!)
      (is (= 2 (count (seat-fires seat))))
      (is (names-row? 1))
      (is (empty? (undelivered-of seat))))

    (testing "a second miss on the row records undelivered and fires
              nothing"
      (walk! 3 close-sitting!)
      (is (= 2 (count (seat-fires seat))))
      (let [us (undelivered-of seat)]
        (is (= 1 (count us)))
        (is (= id (str (get-in (first us) [:inputs :row]))))
        (is (= "wake_item.complete"
               (str (get-in (first us) [:inputs :delivery]))))))

    (testing "a delivered run in between starts the count again"
      (walk! 4 delivered-close!)
      (is (= 2 (count (seat-fires seat))))
      (walk! 5 close-sitting!)
      (is (= 3 (count (seat-fires seat))))
      (is (names-row? 2))
      (is (= 1 (count (undelivered-of seat)))))

    (seat-do! seat :retire)))

;; Ticket 2a9252a4. One close left its handed row undelivered AND left
;; rows in the walk it was never handed. The rewake's waiting text must
;; not keep the queue wake from being marked: it waits beside the text
;; as `wake_textless`, and goes out as its own fire.

(deftest a-rewake-beside-unhanded-rows-keeps-the-queue-wake
  (let [wn :wake-rewake-queue
        fn' :wake-rewake-queue-fires
        batch "rewake-queue"
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat]}
        (linked-seat! "rewakequeueclerk"
                      {:walk "wake_item"
                       :scope [{:kind "wake_item" :actions ["complete"]
                                :filter {:batch batch}}]
                       :wake_on [{:kind "wake_item" :actions ["create"]
                                  :filter {:batch batch}}]
                       :delivers [{:kind "wake_item" :action "complete"}]
                       :release_grace_seconds 0
                       :rows_per_firing 10
                       :fire_interval_seconds 1}
                      fn')
        waiting (nth-sitting! seat 1)
        handed (str (item! batch))
        _ (item! batch)]
    (drain-wakes! wn)
    (Thread/sleep 1200)
    (close-sitting! waiting)
    (drain-wakes! wn)
    (is (= 1 (count (seat-fires seat))))

    (testing "the close fires the rewake naming its row, and the queue
              wake for the row it was never handed is not lost"
      (let [run (nth-sitting! seat 2)]
        (is (true? (:claimed? (seats/claim-rows-atomically!
                               *eng* seat run [handed]))))
        (drain-wakes! wn)
        (Thread/sleep 1200)
        (close-sitting! run)
        (drain-wakes! wn)
        (let [after (rest (seat-fires seat))
              texts (mapv #(get-in % [:inputs :text]) after)]
          (is (some #(str/includes? (str %) handed) texts))
          (is (or (some nil? texts)
                  (true? (get-in (sched-of seat) [:data :wake_pending])))))))

    (seat-do! seat :retire)))

;; ── a red head the poll reads again wakes nobody (ticket 70aca5e6) ──

(defn- pr! [title]
  (:id (:row (inv/create! *eng* :wake_pr {:title title}
                          {:principal elena}))))

(defn- observe! [id head]
  (inv/invoke! *eng* :wake_pr (str id) :observe {:head_sha head}
               {:principal elena}))

(deftest an-observe-on-an-unchanged-head-fires-no-sitting
  (let [wn :wake-same-head
        fn' :wake-same-head-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (linked-seat! "sameheadclerk"
                      {:scope [{:kind "wake_pr" :actions ["observe"]}]
                       :wake_on [{:kind "wake_pr" :actions ["observe"]}]}
                      fn')
        row (pr! "a red pull request")]
    (observe! row "aaa111")
    (drain-wakes! wn)
    (drain-fires! fn')
    (is (= 1 (count (seat-fires seat)))
        "the first head the wake hears wakes the seat")
    (is (= 1 (count (fires-of token))))

    (testing "the poll's next observes on the same head fire nothing"
      (observe! row "aaa111")
      (observe! row "aaa111")
      (drain-wakes! wn)
      (drain-fires! fn')
      (is (= 1 (count (seat-fires seat))))
      (is (not (true? (get-in (sched-of seat) [:data :wake_pending])))
          "and nothing waits behind the damper either"))

    (testing "a new head wakes the seat again"
      (observe! row "bbb222")
      (drain-wakes! wn)
      (is (or (= 2 (count (seat-fires seat)))
              (true? (get-in (sched-of seat) [:data :wake_pending])))))

    (seat-do! seat :retire)))

;; ── a sitting's end, on its own member ──────────────────────────────
;;
;; Ticket 87bc3425 (docs/spec-events.md E-5.4). `rewake-undelivered!`
;; and `release-for-sitting!` left the `:wakes` consumer for the
;; `:sitting-end` member of the consumer family. Each test keeps its
;; own cursor on both, as the two drains above do.

(defn- the-wakes!
  "The `:wakes` consumer as the engine registers it: without the end."
  [cname]
  (let [cache (atom nil)]
    (consumers/drain-consumer! *eng* cname
                               #(wakes/handle-transition! *eng* cache %))))

(defn- cursor-at [c]
  (store/with-tx (:storage *eng*)
    #(store/cursor-get (:storage *eng*) % c)))

(defn- wakes-cursor [cname]
  (cursor-at (str "consumer:" (name cname))))

(defn- close-id [sitting-id]
  (:id (last (filter #(= :close (:action %)) (log-of :sitting sitting-id)))))

(defn- counting-ends
  "Run thunk with the two sitting-end functions counted, not run. →
  [rewakes releases], each the transitions it was called with."
  [thunk]
  (let [rewakes (atom []) releases (atom [])]
    (with-redefs-fn
      {#'wakes/rewake-undelivered! (fn [_ t _] (swap! rewakes conj t) nil)
       #'wakes/release-for-sitting! (fn [_ t _] (swap! releases conj t) nil)}
      thunk)
    [@rewakes @releases]))

(defn- of-sitting [ts sitting-id]
  (count (filter #(= (str sitting-id) (str (:resource-id %))) ts)))

(deftest the-sitting-end-member-starts-at-the-wakes-cursor
  (let [wn :wake-end-seed
        opts {:id "sitting-end-seed" :wakes wn}]
    (the-wakes! wn)
    (task! "before the hand-over")
    (the-wakes! wn)
    (let [at (wakes-cursor wn)]
      (is (some? at))
      (is (= at (wakes/seed-sitting-end! *eng* opts)))
      (is (= at (cursor-at (consumers/member-cursor wakes/sitting-end-family
                                                    (:id opts)))))
      (testing "and a second seed keeps the member where it is"
        (task! "after the hand-over")
        (the-wakes! wn)
        (is (= at (wakes/seed-sitting-end! *eng* opts)))))))

(deftest one-close-runs-each-sitting-end-once
  (let [wn :wake-end-once
        opts {:id "sitting-end-once" :wakes wn}
        _ (the-wakes! wn)
        _ (wakes/seed-sitting-end! *eng* opts)
        seat (seat! "endonceclerk" {})
        run (sitting! seat)
        [rewakes releases]
        (counting-ends
         (fn []
           (close-sitting! run)
           (the-wakes! wn)
           (wakes/drain-sitting-end! *eng* opts)
           (the-wakes! wn)
           (wakes/drain-sitting-end! *eng* opts)))]
    (is (= 1 (of-sitting rewakes run)) "the rewake runs once")
    (is (= 1 (of-sitting releases run)) "the release runs once")
    (is (= (close-id run) (:id (first (filter #(= (str run) (str (:resource-id %)))
                                              releases))))
        "and it is the close that ran it")
    (seat-do! seat :retire)))

(deftest a-parked-sitting-end-and-a-parked-wakes-hold-each-other-nothing
  ;; `sitting!` registers one model per seat: one seat for each half
  (let [seat (seat! "endparkclerk" {})
        other (seat! "endparkotherclerk" {})
        refuse (fn [& _] (throw (ex-info "refused" {})))]
    (testing "a parked :sitting-end member does not hold :wakes"
      (let [wn :wake-end-park-a
            opts {:id "sitting-end-park-a" :wakes wn}
            mine (consumers/member-cursor wakes/sitting-end-family (:id opts))
            _ (the-wakes! wn)
            _ (wakes/seed-sitting-end! *eng* opts)
            run (sitting! seat)]
        (close-sitting! run)
        (consumers/drain-family! *eng* wakes/sitting-end-family
                                 (assoc (wakes/sitting-end-spec *eng* opts)
                                        :deliver refuse))
        (the-wakes! wn)
        (is (< (long (cursor-at mine)) (long (close-id run)))
            "the member is parked before the close")
        (is (>= (long (wakes-cursor wn)) (long (close-id run)))
            ":wakes went past it")))

    (testing "a parked :wakes does not hold the :sitting-end member"
      (let [wn :wake-end-park-b
            opts {:id "sitting-end-park-b" :wakes wn}
            mine (consumers/member-cursor wakes/sitting-end-family (:id opts))
            _ (the-wakes! wn)
            _ (wakes/seed-sitting-end! *eng* opts)
            run (sitting! other)]
        (close-sitting! run)
        (consumers/drain-consumer! *eng* wn refuse)
        (wakes/drain-sitting-end! *eng* opts)
        (is (< (long (wakes-cursor wn)) (long (close-id run)))
            ":wakes is parked before the close")
        (is (>= (long (cursor-at mine)) (long (close-id run)))
            "the member went past it")))

    (seat-do! seat :retire)
    (seat-do! other :retire)))
