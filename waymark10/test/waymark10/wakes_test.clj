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
    counts on every action of its kind, where a transition entry with
    none matches nothing; and `at_least` below one is refused by the
    schema at the create door.
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
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.schedules :as sch]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.wakes :as wakes]
            [waymark10.test.db :as db]
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

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["wake_tasks" "wake_items" "wake_memos" "wake_chats"
   "schedules" "seats" "models" "sittings" "definitions"
   "members" "roles" "grants" "approval_requests" "attachments"
   "subscriptions" "jobs"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_cursors"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *fake* nil)
(def ^:dynamic *fire* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [fake (sch/fake-scheduler)
              fire (sch/fake-fire)
              eng (engine/engine
                   {:storage st
                    :resources [wake-task wake-item wake-memo wake-chat]})]
          (binding [*eng* (assoc eng
                                 :schedule-adapters {:claude_routine fake}
                                 :fire-adapter fire)
                    *fake* fake
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
          :substitute_drop []
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
          :substitute_drop []
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

(defn- restate! [seat-id extra]
  (let [row (raw :seat seat-id)]
    (inv/invoke! *eng* :seat (str seat-id) :restate (restate-body extra)
                 {:principal elena
                  :if-match (inv/etag :seat (str seat-id) (:version row))})))

(defn- seat-do! [seat-id action]
  (inv/invoke! *eng* :seat (str seat-id) action nil {:principal elena}))

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

;; ── 1 · case 36: the match wakes the seat, once ─────────────────────

(deftest a-matching-transition-wakes-the-seat-and-names-the-row
  (let [wn :wake-match
        fn' :wake-match-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (linked-seat! "wakeclerk"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]}
                      fn')
        row-id (task! "the first thing")]
    (task-do! row-id :complete)
    (drain-wakes! wn)

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
        (drain-wakes! wn)
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
      (drain-wakes! wn)
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
        (is (nil? (get-in row [:data :wake_on]))
            "R-12.22: no default is WRITTEN")
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
        (is (nil? (get-in row [:data :wake_on]))
            "R-12.22: no default is WRITTEN, here as anywhere")
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

  (testing "and at restate, on a seat that was born honest"
    (let [seat (seat! "wakehonest"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]})
          p (refusal #(restate! seat {:wake_on [{:kind "nosuchkind"
                                                 :actions ["complete"]}]}))]
      (is (= :wake-on-names-real-kinds (:guard p)))
      (is (str/includes? (str (:detail p)) "nosuchkind"))
      (is (= [{:kind "wake_task" :actions ["complete"]}]
             (get-in (raw :seat seat) [:data :wake_on]))
          "and the stored wake_on did not move")
      (seat-do! seat :retire)))

  (testing "and a restate naming an engine-own kind is refused with the
            kind to wake on instead"
    (let [seat (seat! "wakeownkind" {})
          was (get-in (raw :seat seat) [:data :wake_on])
          p (refusal #(restate! seat {:wake_on [{:kind "sitting"
                                                 :actions ["close"]}]}))]
      (is (= :wake-on-names-no-engine-kind (:guard p)))
      (is (str/includes? (str (:detail p)) "sitting"))
      (is (str/includes? (str (:detail p)) "transcript seal"))
      (is (= was (get-in (raw :seat seat) [:data :wake_on]))
          "and the stored wake_on did not move")
      (seat-do! seat :retire)))

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
        (linked-seat! "countclerk"
                      {:scope count-scope
                       :wake_on [{:kind "wake_item"
                                  :actions ["create"]
                                  :filter {:batch batch}
                                  :at_least 20}]}
                      fn')]

    (testing "nineteen rows wake nobody"
      (dotimes [_ 19] (item! batch))
      (drain-wakes! wn)
      (is (empty? (seat-fires seat)))
      (drain-fires! fn')
      (is (empty? (fires-of token)))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))
          "and nothing waits on the schedule row: a count below the
           size is not a damped match, it is no match at all"))

    (testing "the twentieth fires once, and the text carries the count"
      (item! batch)
      (drain-wakes! wn)
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
        (linked-seat! "countfilterclerk"
                      {:scope count-scope
                       :wake_on [{:kind "wake_item"
                                  :actions ["complete"]
                                  :filter {:batch batch :state "complete"}
                                  :at_least 2}]}
                      fn')
        ids (vec (repeatedly 3 #(item! batch)))]

    (testing "three rows waiting is not two rows complete"
      (drain-wakes! wn)
      (is (empty? (seat-fires seat))))

    (testing "the first completion counts one, which is not enough"
      (item-do! (first ids) :complete)
      (drain-wakes! wn)
      (is (empty? (seat-fires seat)))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (testing "the second reaches the size, and the count is the
              filter's two — not the batch's three, and not the one
              row the kind's own default filter would have left open"
      (item-do! (second ids) :complete)
      (drain-wakes! wn)
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
        picky (linked-seat! "pickyclerk"
                            {:scope count-scope
                             :wake_on [{:kind "wake_item"
                                        :actions ["complete"]
                                        :filter {:batch mine}}]}
                            fn')
        anyone (linked-seat! "anyoneclerk"
                             {:scope count-scope
                              :fire_interval_seconds 1
                              :wake_on [{:kind "wake_item"
                                         :actions ["complete"]}]}
                             fn')
        theirs-id (item! theirs)
        mine-id (item! mine)]

    (testing "another batch's row completes, and the filtered seat is
              not woken — nothing waits on its schedule row either,
              because this is no match at all rather than a match the
              damper held"
      (item-do! theirs-id :complete)
      (drain-wakes! wn)
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
      (drain-wakes! wn)
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

(deftest a-comma-wake-filter-on-a-field-without-in-is-refused-at-restate
  (let [seat (seat! "wakecomma"
                    {:wake_on [{:kind "wake_task" :actions ["complete"]}]})
        p (refusal #(restate! seat {:wake_on [{:kind "wake_task"
                                               :actions ["complete"]
                                               :filter {:title "a,b"}}]}))]
    (is (= :wake-on-any-of-needs-in (:guard p)))
    (is (str/includes? (str (:detail p)) "title")
        "the refusal names the field, not 'invalid wake_on'")
    (seat-do! seat :retire)))

;; ── 11 · a count entry with no actions counts on every action ───────

(deftest an-empty-actions-list-reads-differently-on-the-two-entries
  (let [transition {:kind "wake_item" :actions ["create"]}
        count-all {:kind "wake_item" :actions [] :at_least 5}
        transition-all {:kind "wake_item" :actions []}]
    (testing "a count entry naming no action counts on every action of
              its kind (R-12.24's default)"
      (is (wakes/matches? count-all :wake_item :touch))
      (is (wakes/matches? count-all :wake_item :create))
      (is (not (wakes/matches? count-all :wake_task :create))
          "its kind, and no other"))
    (testing "a transition entry naming none still matches nothing: a
              wake is an action happening, not a kind existing"
      (is (not (wakes/matches? transition-all :wake_item :create))))
    (testing "and the entries a transition matches come back in the
              order the seat wrote them"
      (is (= [transition count-all]
             (wakes/matching-entries [transition transition-all count-all]
                                     :wake_item :create)))
      (is (= [count-all]
             (wakes/matching-entries [transition transition-all count-all]
                                     :wake_item :complete))))))

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
             (get-in (raw :seat seat) [:data :wake_on])))
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
        (linked-seat! "instructedclerk"
                      {:instructions the-instructions
                       :wake_on [{:kind "wake_task" :actions ["complete"]}]}
                      fn')
        row-id (task! "the thing that woke it")]
    (task-do! row-id :complete)
    (drain-wakes! wn)
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
        (linked-seat! "emptyclerk"
                      {:scope count-scope
                       :wake_on [{:kind "wake_item"
                                  :actions ["complete"]
                                  :filter {:batch batch}
                                  :at_most 0}]}
                      fn')
        ids (vec (repeatedly 2 #(item! batch)))]

    (testing "two rows arrive, and `create` is not one of this entry's
              actions, so nothing is counted and nothing fires"
      (drain-wakes! wn)
      (is (empty? (seat-fires seat)))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))))

    (testing "the first completion leaves one row waiting, which is
              not an empty queue"
      (item-do! (first ids) :complete)
      (drain-wakes! wn)
      (is (empty? (seat-fires seat)))
      (is (not (get-in (sched-of seat) [:data :wake_pending]))
          "and nothing waits on the schedule row: a queue above the
           size is no match at all, not a match the damper held")
      (drain-fires! fn')
      (is (empty? (fires-of token))))

    (testing "the second empties the filter, and the seat fires once"
      (item-do! (second ids) :complete)
      (drain-wakes! wn)
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
             (get-in (raw :seat seat) [:data :wake_on])))
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
            (linked-seat! "mentionclerk"
                          {:wake_on [{:kind "wake_chat"
                                      :actions ["observe_mention"]
                                      :filter {:external_id chat}
                                      :settle_seconds 300}]}
                          fn')]

        (testing "a plain message moves the row and wakes nobody: the
                  etag changed, the mention did not"
          (swap! chat-feed-state assoc-in [chat :last_message_at]
                 "2026-09-20T18:00:00Z")
          (is (= 1 (:rewritten (mirror/resync! *eng* :wake_chat))))
          (drain-wakes! wn)
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
          (drain-wakes! wn)
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
          (drain-wakes! wn)
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
          (drain-wakes! wn)
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
            (linked-seat! "beatclerk"
                          {:wake_on [{:kind "wake_chat"
                                      :actions ["observe_mention"]
                                      :filter {:external_id chat}
                                      :settle_seconds 300}]}
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
          (drain-wakes! wn)
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
;; A run that dies before it sits spends no key, opens no sitting and
;; leaves its count wake spent. The clock sweep writes it down as a
;; closed `missed` sitting and arms the wake again, once.

(deftest a-fire-nobody-sat-in-leaves-a-missed-sitting-and-its-wake-comes-back
  (let [fn' :wake-missed-fires
        _ (drain-fires! fn')
        batch "missed-fire"
        ;; whole seconds, so an instant reads back as it was written
        ^Instant t0 (.truncatedTo (Instant/now) java.time.temporal.ChronoUnit/SECONDS)
        clock (atom t0)
        at (fn [secs] (reset! clock (.plusSeconds ^Instant t0 (long secs))))]
    (binding [*eng* (assoc *eng* :now-fn (fn [] @clock))]
      (let [{:keys [seat]}
            (linked-seat! "missedclerk"
                          {:scope count-scope
                           :instructions "Read the fire text and do what it says."
                           :wake_on [{:kind "wake_item"
                                      :actions ["create"]
                                      :filter {:batch batch}
                                      :at_least 2}]}
                          fn')
            seat-row #(raw :seat seat)
            hold! (fn [] (seats/hold-fire-key! *eng* (seat-row) @clock))
            missed #(store/with-tx (:storage *eng*)
                      (fn [tx]
                        (store/query-rows (:storage *eng*) tx :sitting
                                          {:seat (str seat) :missed true}
                                          {:limit 50})))
            held-hashes #(mapv (fn [e] (str (:hash e)))
                               (get-in (seat-row) [:data :fire_keys]))
            clear-pending! #(let [s (sched-of seat)]
                              (store/with-tx (:storage *eng*)
                                (fn [tx]
                                  (store/update-data! (:storage *eng*) tx :schedule
                                                      (str (:id s))
                                                      (dissoc (:data s) :wake_pending)
                                                      (:next-flip-at s)))))]
        ;; the queue the count wake watches holds its size, so the wake
        ;; the fire spent still has work to do
        (item! batch)
        (item! batch)

        (testing "a fire whose key was spent makes no missed sitting"
          (let [k (hold!)]
            (is (string? k))
            (is (seats/fire-key-held? *eng* (seat-row) k)
                "the entry carries the fire's instant beside its expiry")
            (is (= (str @clock)
                   (str (:fired_at (first (get-in (seat-row) [:data :fire_keys]))))))
            (is (true? (seats/spend-fire-key! *eng* (seat-row) k)))
            (at 700)
            (is (= 0 (wakes/sweep-missed! *eng*)))
            (is (empty? (missed)))
            (is (not (get-in (sched-of seat) [:data :wake_pending])))))

        (testing "a fire whose key is unspent after 600s makes one closed
                  missed sitting, takes the entry off the seat, and arms
                  the count wake again"
          (let [^Instant fired (at 1000)
                stale (hold!)
                _ (at 1300)
                fresh (hold!)]
            (is (= 0 (wakes/sweep-missed! *eng*))
                "five minutes on, the run may still be starting")
            (at 1601)
            (is (= 1 (wakes/sweep-missed! *eng*)))
            (let [rows (missed)
                  d (:data (first rows))]
              (is (= 1 (count rows)))
              (is (= :closed (:state (first rows))))
              (is (= "fired" (str (:mode d))))
              (is (= 0 (:input_tokens d) (:output_tokens d) (:turns d)))
              (is (= fired (Instant/parse (str (:started_at d)))))
              (is (= @clock (Instant/parse (str (:ended_at d)))))
              (is (str/includes? (str (:note d))
                                 (str "Fired at " fired
                                      "; no session sat within 600s."))))
            (is (= [(seats/key-hash fresh)] (held-hashes))
                "the missed entry is gone, and the fresh one stays")
            (is (nil? (seats/seat-for-key *eng* "missedclerk" stale))
                "so a late sit with that key is refused as today")
            (is (true? (get-in (sched-of seat) [:data :wake_pending]))
                "the count wake still holds, so the queue fires again")))

        (testing "the sweep over the same state makes no second row"
          (is (= 0 (wakes/sweep-missed! *eng*)))
          (is (= 1 (count (missed)))))

        (testing "a second missed fire in a row makes its sitting but
                  does not arm the wake again"
          (clear-pending!)
          (at 2000)
          (is (= 1 (wakes/sweep-missed! *eng*))
              "the fresh key of the last round has now missed too")
          (is (= 2 (count (missed))))
          (is (empty? (held-hashes)))
          (is (not (get-in (sched-of seat) [:data :wake_pending]))))

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
        (linked-seat! "haltclerk"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]}
                      fn')]
    (is (true? (seats/seat-halt! *eng* seat "model_not_held"
                                 "This session declares nothing and haltclerk is held for 1 model(s).")))

    (testing "a matching transition fires nothing, and the wake waits"
      (task-do! (task! "a thing behind the halt") :complete)
      (drain-wakes! wn)
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

;; ── a broken schedule holds its wakes (waymark ticket bb19404d) ─────

(deftest a-broken-schedule-holds-its-wake-until-it-is-linked-again
  (let [wn :wake-broken
        fn' :wake-broken-fires
        _ (drain-wakes! wn)
        _ (drain-fires! fn')
        {:keys [seat token]}
        (linked-seat! "brokenclerk"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]
                       :fire_interval_seconds 1}
                      fn')]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the provider refuses") :complete)
      (drain-wakes! wn)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of token))) "the refused POST went out once")
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the broken row fires nothing and stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while it is broken") :complete)
        (drain-wakes! wn)
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
                     :wake_on [{:kind "wake_task" :actions ["complete"]}]
                     :fire_interval_seconds 1})
        _ (drain-fires! fn')]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the chair's Routine refuses") :complete)
      (drain-wakes! wn)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of token))) "the refused POST went out once")
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the broken row stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while the chair is broken") :complete)
        (drain-wakes! wn)
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
        (linked-seat! "runnersetclerk"
                      {:wake_on [{:kind "wake_task" :actions ["complete"]}]
                       :fire_interval_seconds 1}
                      fn')
        runner-token "rk-test-runnersetrunner-0123456789abcdef"]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the provider refuses this seat") :complete)
      (drain-wakes! wn)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the broken row stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while the seat is broken") :complete)
        (drain-wakes! wn)
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
                     :wake_on [{:kind "wake_task" :actions ["complete"]}]
                     :fire_interval_seconds 1})
        _ (drain-fires! fn')]
    (try
      (sch/answer! *fire* 401)
      (task-do! (task! "the one the pool's only runner refuses") :complete)
      (drain-wakes! wn)
      (drain-fires! fn')
      (sch/answer! *fire* nil)
      (is (= 1 (count (fires-of dead-token))) "the refused POST went out once")
      (is (= :broken (:state (sched-of seat))))

      (testing "a wake on the held row stays pending"
        (Thread/sleep 1100)
        (task-do! (task! "a match while the pool is broken") :complete)
        (drain-wakes! wn)
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
