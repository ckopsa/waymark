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
            [waymark10.server.schedules :as sch]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.wakes :as wakes]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

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
    :filterable {:state #{:eq :in} :batch #{:eq}}
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

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["wake_tasks" "wake_items" "wake_memos" "schedules" "seats" "models" "sittings" "definitions"
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
              eng (engine/engine {:storage st
                                  :resources [wake-task wake-item wake-memo]})]
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
