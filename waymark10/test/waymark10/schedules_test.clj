(ns waymark10.schedules-test
  "The schedule kind and its adapter (spec-seat.md §12, acceptance
  cases 25 and 26).

  What this suite proves, in the spec's own order: a seat's birth
  mints a schedule in `pending` and stamps its id onto the seat
  (R-12.1, R-12.2); the consumer's first drain makes exactly one copy
  at the provider carrying the seat's name, its cadence as a cron, the
  schedule's model and the fixed pointer prompt, and the row goes
  `live` with `external_id` and `pushed_at` (R-12.2, R-12.3); a
  `restate` of the cadence pushes again with the new cron and never
  touches the prompt; `park` pauses, `unpark` resumes, `retire` and
  `merge` delete; a read-back that differs writes `drift` and repairs
  nothing (R-12.3); and a provider with no token serves the schedule
  `broken` with a note rather than failing at boot (R-12.11). The cron
  spelling is proved on its own, without a database, for the four
  cadences the brief names.

  The seat and the model here are the REAL framework kinds
  (server/seats.clj) — both are `:always` in the module table, so an
  engine assembled with no resources at all already serves them, and
  the suite's own `:resources` is empty.

  Section 9 is the chair (bead waymark-fp62.7.23): the text one fire
  carries, composed from the seat row, and the seat with no link of
  its own that fires through its model's one Routine.

  Real Postgres (WAYMARK10_TEST_DSN); no network — the fake scheduler
  and the fake fire endpoint stand at the provider."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.schema :as schema]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.runner-links :as rl]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["schedules" "seats" "models" "sittings" "definitions" "members" "roles"
   "grants" "approval_requests" "attachments" "subscriptions" "jobs"
   "runner_links" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_cursors" "waymark10_drafts"])

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
              eng (engine/engine {:storage st :resources []})]
          (binding [*eng* (assoc eng
                                 :schedule-adapters {:claude_routine fake}
                                 :fire-adapter fire)
                    *fake* fake
                    *fire* fire]
            (f)))
        (finally (pg/close! st))))))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))

;; ── writers ─────────────────────────────────────────────────────────

(def ^:private a-scope
  "A scope the four scope guards pass: one registered kind, one of its
  real action names, and nothing private."
  [{:kind "model" :actions ["retire"]}])

(def ^:private a-charter
  "Decide whether a message asks something of this house.")

(defn- model! [nm]
  (:id (:row (inv/create! *eng* :model
                          {:name nm :display nm :vendor "anthropic"
                           :tier "strong"
                           :price_input_per_mtok 3M
                           :price_output_per_mtok 15M
                           :price_cache_read_per_mtok 0.3M
                           :price_cache_write_per_mtok 3.75M}
                          {:principal elena}))))

(defn- seat!
  ([nm cadence held] (seat! nm cadence held {}))
  ([nm cadence held extra]
   (:id (:row (inv/create! *eng* :seat
                           (merge {:name nm
                                   :charter a-charter
                                   :scope a-scope
                                   :substitute_drop []
                                   :held_for (vec held)
                                   :substitute_for []
                                   :standing_ttl_seconds 604800
                                   :cadence_seconds cadence
                                   :budget_usd_per_week 5M
                                   :sitting_budget_tokens 60000
                                   :rows_per_firing 20}
                                  extra)
                           {:principal elena})))))

(defn- raw [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx] (store/load-row (:storage *eng*) tx kind (str id) {}))))

(defn- restate-cadence!
  "The seat's `restate` states the office WHOLE — it is not a patch —
  and it is fenced, so the caller carries the row's etag. `held_for`
  is restated unchanged, which is why no `note` is owed
  (`step-carries-a-note` asks only when the models move)."
  [seat-id cadence]
  (let [row (raw :seat seat-id)]
    (inv/invoke! *eng* :seat (str seat-id) :restate
                 {:charter a-charter
                  :scope a-scope
                  :substitute_drop []
                  :held_for (vec (or (get-in row [:data :held_for]) []))
                  :substitute_for []
                  :standing_ttl_seconds 604800
                  :cadence_seconds cadence
                  :budget_usd_per_week 5M
                  :sitting_budget_tokens 60000
                  :rows_per_firing 20}
                 {:principal elena
                  :if-match (inv/etag :seat (str seat-id) (:version row))})))

(defn- seat-do! [seat-id action]
  (inv/invoke! *eng* :seat (str seat-id) action nil {:principal elena}))

;; ── readers ─────────────────────────────────────────────────────────

(defn- sched-of [seat-id] (sch/schedule-for-seat *eng* seat-id))

(defn- drain!
  "One synchronous drain under a named cursor. Called once BEFORE the
  writes it is about, so the cursor seeds at the newest transition and
  the test hears only its own — clojure.test promises no order between
  deftests, and a from-origin drain in one of them would replay
  another's."
  [cname]
  (consumers/drain-consumer! *eng* cname (sch/consumer-fn *eng*)))

(defn- copy-of [schedule-row]
  (sch/copy *fake* (get-in schedule-row [:data :external_id])))

(defn- check-drift! [schedule-row]
  (sch/check-drift! *eng* (sch/adapters-of *eng*) schedule-row))

;; ── 1 · the cron spelling (no database) ─────────────────────────────

(deftest cron-spells-the-four-cadences
  (testing "the cadences the brief names"
    (is (= "*/15 * * * *" (sch/cron-of 900)))
    (is (= "0 * * * *" (sch/cron-of 3600)))
    (is (= "0 */6 * * *" (sch/cron-of 21600)))
    (is (= "0 0 * * *" (sch/cron-of 86400))))
  (testing "a step always divides its field — an uneven one is not the cadence anybody asked for"
    (is (= "*/5 * * * *" (sch/cron-of 300)))
    (is (= "*/30 * * * *" (sch/cron-of 1800)))
    (is (= "0 */12 * * *" (sch/cron-of 43200))))
  (testing "at least this often: a cadence cron cannot say exactly rounds DOWN"
    (is (= "*/6 * * * *" (sch/cron-of 420)) "seven minutes fires every six")
    (is (= "0 * * * *" (sch/cron-of 5400)) "ninety minutes fires hourly"))
  (testing "the corners, each decided"
    (is (= "* * * * *" (sch/cron-of 10)) "cron has no finer field than the minute")
    (is (= "* * * * *" (sch/cron-of 60)))
    (is (= "0 0 * * 0" (sch/cron-of (* 7 86400))) "a real week, not */7 on the day of month")
    (is (= "0 0 */3 * *" (sch/cron-of (* 3 86400))))
    (is (= "0 0 1 * *" (sch/cron-of (* 90 86400)))
        "past a month cron cannot say it; monthly is the coarsest honest answer")))

;; ── 2 · the prompt is the pointer, and only the pointer ─────────────

(deftest prompt-is-the-fixed-pointer
  (let [p (sch/pointer-prompt "inbox-clerk")]
    (is (str/includes? p "You sit in the seat `inbox-clerk`."))
    (is (str/includes? p "Read the seat row with `waymark_get`"))
    (is (str/includes? p "Take only the doors the envelope offers."))
    (is (str/includes? p "When the seat says halted or parked, say why and stop."))
    (testing "and nothing else — R-12.10's lazy-loading contract"
      (is (not (str/includes? p a-charter)))
      (is (< (count p) 400)))))

;; ── 3 · acceptance case 25: birth, push, restate, park, retire ──────

(deftest a-seat-gets-a-schedule-and-a-copy
  (let [cn :sched-birth
        replay :sched-birth-replay
        ;; both cursors seed HERE, before this test's writes
        _ (drain! cn)
        _ (drain! replay)
        sonnet (model! "claude-sonnet-5")
        before (count (sch/copies *fake*))
        seat-id (seat! "inbox-clerk" 3600 [sonnet])]

    (testing "the schedule row is minted pending, before any copy exists"
      (let [row (sch/ensure-schedule! *eng* (raw :seat seat-id))]
        (is (= :pending (:state row)))
        (is (= (str seat-id) (get-in row [:data :seat])))
        (is (= "claude_routine" (get-in row [:data :provider])))
        (is (= sonnet (get-in row [:data :model]))
            "the model defaults to the first of the seat's held_for")
        (is (nil? (get-in row [:data :external_id])))))

    (testing "and the engine writes its id back onto the seat"
      (is (= (str (:id (sched-of seat-id)))
             (get-in (raw :seat seat-id) [:data :schedule]))))

    (testing "the consumer's drain makes exactly one copy"
      (drain! cn)
      (is (= (inc before) (count (sch/copies *fake*)))))

    (let [row (sched-of seat-id)
          copy (copy-of row)]
      (testing "the row is live, and says what was written and when"
        (is (= :live (:state row)))
        (is (some? (get-in row [:data :external_id])))
        (is (some? (get-in row [:data :pushed_at])))
        (is (nil? (get-in row [:data :note]))))
      (testing "the copy carries the seat's name, its cadence as cron, the model, the prompt"
        (is (= "inbox-clerk" (:name copy)))
        (is (= "0 * * * *" (:cron copy)))
        (is (= "claude-sonnet-5" (:model copy)))
        (is (= (sch/pointer-prompt "inbox-clerk") (:prompt copy)))
        (is (true? (:enabled copy)))))

    (testing "a replayed drain writes no second copy — at-least-once, and the client token is the dedupe"
      (let [n (count (sch/copies *fake*))
            xid (get-in (sched-of seat-id) [:data :external_id])]
        (drain! replay)
        (is (= n (count (sch/copies *fake*))))
        (is (= xid (get-in (sched-of seat-id) [:data :external_id]))
            "the same copy came back rather than a second one being minted")
        (is (= :live (:state (sched-of seat-id))))))

    (testing "a restate of the cadence pushes again with the new cron — and the SAME prompt"
      (let [pushed-before (get-in (sched-of seat-id) [:data :pushed_at])]
        (restate-cadence! seat-id 21600)
        (drain! cn)
        (let [row (sched-of seat-id)
              copy (copy-of row)]
          (is (= "0 */6 * * *" (:cron copy)))
          (is (= "inbox-clerk" (:name copy)))
          (is (= (sch/pointer-prompt "inbox-clerk") (:prompt copy))
              "R-12.3: the prompt never changes after the copy is made")
          (is (= :live (:state row)))
          (is (not= pushed-before (get-in row [:data :pushed_at]))))))

    (testing "park pauses the copy; unpark resumes it"
      (seat-do! seat-id :park)
      (drain! cn)
      (is (= :paused (:state (sched-of seat-id))))
      (is (false? (:enabled (copy-of (sched-of seat-id)))))
      (seat-do! seat-id :unpark)
      (drain! cn)
      (is (= :live (:state (sched-of seat-id))))
      (is (true? (:enabled (copy-of (sched-of seat-id))))))

    (testing "retire deletes the copy and ends the row"
      (let [xid (get-in (sched-of seat-id) [:data :external_id])]
        (seat-do! seat-id :retire)
        (drain! cn)
        (is (nil? (sch/copy *fake* xid)))
        (is (= :ended (:state (sched-of seat-id))))))))

(deftest merge-deletes-the-copy-too
  ;; `merge` needs a second seat to fold into and a confirm echo, and
  ;; what is under test is the consumer's LISTEN LIST — so the
  ;; transition is handed to `handle-transition!` directly, in exactly
  ;; the shape the drain would deliver it.
  (let [cn :sched-merge
        _ (drain! cn)
        seat-id (seat! "composer" 86400 [])]
    (drain! cn)
    (let [row (sched-of seat-id)
          xid (get-in row [:data :external_id])]
      (is (= :live (:state row)))
      (is (= "0 0 * * *" (:cron (sch/copy *fake* xid))))
      (is (nil? (:model (sch/copy *fake* xid)))
          "an empty held_for names no model; the provider uses its own")
      (sch/handle-transition! *eng* (sch/adapters-of *eng*)
                              {:kind :seat :action :merge
                               :resource-id (str seat-id)})
      (is (nil? (sch/copy *fake* xid)))
      (is (= :ended (:state (sched-of seat-id))))
      (testing "and a replay of the same transition is a no-op, not a 409"
        (sch/handle-transition! *eng* (sch/adapters-of *eng*)
                                {:kind :seat :action :merge
                                 :resource-id (str seat-id)})
        (is (= :ended (:state (sched-of seat-id))))))))

;; ── 4 · acceptance case 26, first half: the read-back reports ───────

(deftest a-read-back-that-differs-writes-drift
  (let [cn :sched-drift
        _ (drain! cn)
        haiku (model! "claude-haiku-4-5")
        seat-id (seat! "drift-clerk" 900 [haiku])]
    (drain! cn)
    (let [xid (get-in (sched-of seat-id) [:data :external_id])]
      (is (= "*/15 * * * *" (:cron (sch/copy *fake* xid))))

      (testing "a clean read-back stamps seen_at and leaves drift empty"
        (check-drift! (sched-of seat-id))
        (let [row (sched-of seat-id)]
          (is (= :live (:state row)))
          (is (some? (get-in row [:data :seen_at])))
          (is (nil? (get-in row [:data :drift])))))

      (testing "somebody edits the Routine by hand: the difference lands in drift"
        (sch/tamper! *fake* xid {:cron "0 0 * * *" :model "something-else"})
        (check-drift! (sched-of seat-id))
        (let [d (get-in (sched-of seat-id) [:data :drift])]
          (is (some? d))
          (is (str/includes? d "cron"))
          (is (str/includes? d "model"))
          (is (str/includes? d "*/15 * * * *"))))

      (testing "and nothing is repaired — the row is the truth, the report is the point"
        (is (= "0 0 * * *" (:cron (sch/copy *fake* xid))))
        (is (= :live (:state (sched-of seat-id)))))

      (testing "a paused copy under a live row is drift too — the seat looks live and nothing wakes"
        (sch/tamper! *fake* xid {:cron "*/15 * * * *"
                                 :model "claude-haiku-4-5"
                                 :enabled false})
        (check-drift! (sched-of seat-id))
        (is (str/includes? (get-in (sched-of seat-id) [:data :drift]) "paused")))

      (testing "the copy put back where the row says clears drift"
        (sch/tamper! *fake* xid {:enabled true})
        (check-drift! (sched-of seat-id))
        (is (nil? (get-in (sched-of seat-id) [:data :drift]))))

      (testing "a prompt edited at the provider is NOT drift — R-12.3 froze it"
        (sch/tamper! *fake* xid {:prompt "something a person typed"})
        (check-drift! (sched-of seat-id))
        (is (nil? (get-in (sched-of seat-id) [:data :drift]))))

      (testing "and the update push never sends the prompt back over it"
        (restate-cadence! seat-id 3600)
        (drain! cn)
        (is (= "0 * * * *" (:cron (sch/copy *fake* xid))))
        (is (= "something a person typed" (:prompt (sch/copy *fake* xid)))))

      (testing "the sweep walks the live rows"
        (is (pos? (sch/sweep-drift! *eng*)))))))

;; ── 5 · acceptance case 26, second half: no token (R-12.11) ─────────

(deftest no-token-is-a-boot-that-says-so
  (testing "building the adapters off an empty environment throws nothing"
    (let [adapters (sch/from-env (constantly nil))]
      (is (= #{:claude_routine :jules :cron} (set (keys adapters))))
      (doseq [[provider a] adapters]
        (is (satisfies? sch/ScheduleAdapter a)
            (str provider " still answers an adapter")))))

  (testing "and the sentence names the missing key, per provider"
    (let [no-url (sch/from-env (constantly nil))
          no-token (sch/from-env {"WAYMARK10_ROUTINES_URL" "https://example.invalid/v1"})]
      (is (str/includes? (try (sch/read-copy (:claude_routine no-url) "x")
                              (catch Exception e (ex-message e)))
                         "WAYMARK10_ROUTINES_URL"))
      (is (str/includes? (try (sch/read-copy (:claude_routine no-token) "x")
                              (catch Exception e (ex-message e)))
                         "WAYMARK10_ROUTINES_TOKEN"))
      (is (str/includes? (try (sch/create-copy (:jules no-url) {})
                              (catch Exception e (ex-message e)))
                         "Jules"))))

  (testing "a schedule pushed through an uncredentialed provider is broken, with the note"
    (let [cn :sched-no-token
          heal :sched-no-token-heal
          _ (drain! cn)
          _ (drain! heal)
          seat-id (seat! "uncredentialed" 3600 [])
          blind (assoc *eng* :schedule-adapters
                       {:claude_routine (:claude_routine
                                         (sch/from-env (constantly nil)))})]
      (consumers/drain-consumer! blind cn (sch/consumer-fn blind))
      (let [row (sch/schedule-for-seat blind seat-id)]
        (is (= :broken (:state row)))
        (is (str/includes? (get-in row [:data :note]) "WAYMARK10_ROUTINES_URL"))
        (is (nil? (get-in row [:data :external_id]))
            "nothing was minted, so nothing is claimed"))

      (testing "and the credential arriving heals it on the next push, with no second schedule"
        ;; `heal` seeded before the seat was opened, so this drain
        ;; re-delivers the very events the blind engine refused
        (drain! heal)
        (let [row (sched-of seat-id)]
          (is (= :live (:state row)))
          (is (nil? (get-in row [:data :note])))
          (is (= "uncredentialed" (:name (copy-of row)))))))))

;; ── 6 · the adapter is unreachable ──────────────────────────────────

(deftest an-unreachable-provider-breaks-the-row-and-not-the-drain
  (let [cn :sched-down
        _ (drain! cn)
        seat-id (seat! "down-clerk" 3600 [])]
    (sch/down! *fake* "the scheduler refused the connection")
    (testing "the drain does not park: the throw lands on the row"
      (is (pos? (drain! cn)))
      (let [row (sched-of seat-id)]
        (is (= :broken (:state row)))
        (is (= "the scheduler refused the connection" (get-in row [:data :note])))))
    (testing "and the provider coming back heals it"
      (sch/down! *fake* false)
      (restate-cadence! seat-id 900)
      (drain! cn)
      (let [row (sched-of seat-id)]
        (is (= :live (:state row)))
        (is (nil? (get-in row [:data :note])))
        (is (= "*/15 * * * *" (:cron (copy-of row))))))))

;; ── 7 · one schedule per seat, and the capability is named ──────────

(deftest one-seat-one-schedule
  (let [cn :sched-one
        _ (drain! cn)
        seat-id (seat! "only-once" 3600 [])]
    (drain! cn)
    (is (some? (sched-of seat-id)))
    (testing "a second schedule for the same seat is refused at the door"
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/create! *eng* :schedule
                                {:seat (str seat-id) :provider "claude_routine"}
                                {:principal sch/system-actor}))))
    (testing "and `ensure-schedule!` answers the standing row rather than asking"
      (is (= (:id (sched-of seat-id))
             (:id (sch/ensure-schedule! *eng* (raw :seat seat-id))))))
    (testing "a person cannot mint one either — the engine owns this kind"
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/create! *eng* :schedule
                                {:seat (str (seat! "hand-made" 3600 []))
                                 :provider "claude_routine"}
                                {:principal elena}))))))

(deftest the-credential-is-a-named-power
  (testing "R-12.11: the token is in the registry's vocabulary, dotted, with a sentence"
    (is (= "schedule.write" sch/write-capability-token))
    (is (= "schedule.write" (:token sch/write-capability)))
    (is (str/includes? (:token sch/write-capability) "."))
    (is (<= 1 (count (:description sch/write-capability)) 240))
    (is (<= (count (:enforced_by sch/write-capability)) 120))
    (is (str/includes? (:description sch/write-capability) "never granted"))))

;; ── 8 · the declaration itself ──────────────────────────────────────

(deftest the-declaration-says-what-section-12-asks
  (let [rd (get (inv/resources *eng*) :schedule)]
    (is (= "schedules" (:plural rd)))
    (is (= :system (:nav rd)))
    (is (= :pending (:initial rd)))
    (is (every? (set (:states rd)) [:pending :live :paused :broken]))
    (is (= #{:seat :provider :model :external_id :pushed_at :seen_at
             :drift :note
             ;; the fire link (R-12.18), and the damper's mark the wake
             ;; consumer writes (R-12.22)
             :fire_url :fire_token :last_fired_at :last_run_url
             :wake_pending :wake_fired_at :wake_due_at :last_halted_wake
             ;; a throttle's own instant: the pending wake waits for it
             :retry_after
             ;; the transitions a damped wake heard (waymark-fp62.21)
             :wake_heard
             ;; the runner pool and the link the last run took (d16b71bf)
             :runners :last_runner
             ;; the order the pool is tried in (529deb73)
             :runner_order}
           (set (schema/entry-keys (:schema rd)))))
    (testing "every engine-written door is hidden from a person"
      (doseq [a [:claim :observe :pause :resume :fail :end :fired]]
        (is (some :hide (get-in rd [:actions a :guards]))
            (str a " is the engine's, not a person's"))))
    (testing "and the human doors are the model and the link (R-12.1, R-12.18)"
      (is (not-any? :hide (get-in rd [:actions :restate :guards])))
      (is (= #{:model}
             (set (schema/entry-keys (:input (get-in rd [:actions :restate])))))
          "a person restates the model and nothing else")
      (doseq [a [:link :link_like :unlink]]
        (is (not-any? :hide (get-in rd [:actions a :guards]))
            (str a " is a person's door")))
      (is (= #{:fire_url :token}
             (set (schema/entry-keys (:input (get-in rd [:actions :link]))))))
      (is (= #{:like}
             (set (schema/entry-keys (:input (get-in rd [:actions :link_like])))))
          "the copy names a row and never a credential"))
    (testing "the deviations are on the record"
      (is (seq (:deviations rd)))
      (is (some #(str/includes? % "mirror") (:deviations rd))))))

;; ── 9 · the chair: one Routine for each model (waymark-fp62.7.23) ───

(def ^:private a-chair-url
  "https://api.anthropic.com/v1/claude_code/routines/trig_01CHAIR/fire")

(def ^:private a-chair-token "rk-test-chair-0123456789abcdef")

(def ^:private a-seat-url
  "https://api.anthropic.com/v1/claude_code/routines/trig_01SEAT/fire")

(def ^:private a-seat-token "rk-test-seat-0123456789abcdef")

(def ^:private the-instructions
  "Read the fire text and do what it says. Sit in the seat it names, then walk the rows the sit hands you.")

(defn- link-model!
  "A person links the Routine they made for this model — the one every
  seat it is the chair of fires through."
  [model-id url token]
  (inv/invoke! *eng* :model (str model-id) :link
               {:fire_url url :token token} {:principal elena}))

(defn- link-schedule! [schedule-id url token]
  (inv/invoke! *eng* :schedule (str schedule-id) :link
               {:fire_url url :token token} {:principal elena}))

(defn- fire-seat!
  "A person's fire. The door is not idempotent, so every call carries
  its own key — fire_door_test's posture, for its reason."
  [seat-id text]
  (inv/invoke! *eng* :seat (str seat-id) :fire (when text {:text text})
               {:principal elena
                :idempotency-key (str "chair-test:" (random-uuid))}))

(defn- log-of [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (store/transitions (:storage *eng*) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- fires-of
  "The POSTs that carried this token, in order. The fake provider is
  shared by every deftest here, so a count of all its fires would be a
  count of the suite."
  [token]
  (filterv #(= token (:token %)) (sch/fires *fire*)))

(deftest fire-text-is-composed-from-the-seat-row
  ;; PURE, and no database: the composition is a function of the row
  ;; and the prose, which is what lets the consumer stay one call.
  (let [seat {:id "seat_01" :data {:name "inbox-clerk"
                                   :instructions the-instructions}}
        bare {:id "seat_02" :data {:name "composer"}}]
    (testing "a seat with no instructions fires the prose it always fired"
      (is (= "Walk the three messages." (sch/fire-text bare "Walk the three messages.")))
      (is (nil? (sch/fire-text bare nil))
          "and a textless fire stays textless — that run walks the queue")
      (is (nil? (sch/fire-text bare ""))))

    (testing "instructions and no prose: the instructions, then the seat line"
      (is (= (str the-instructions "\n\nSeat: seat_01 (inbox-clerk).")
             (sch/fire-text seat nil))))

    (testing "instructions and prose: the prose inside the block they name"
      (is (= (str the-instructions "\n\n"
                  "Seat: seat_01 (inbox-clerk).\n\n"
                  "<routine-fire-payload>\n"
                  "Walk the three messages.\n"
                  "</routine-fire-payload>")
             (sch/fire-text seat "Walk the three messages."))))

    ;; R-12.37: the engine mints a key for each firing, and the line
    ;; that carries it sits under the line that names the seat, because
    ;; the sit takes the two together.
    (testing "the key of one firing rides under the seat line"
      (is (= (str the-instructions "\n\n"
                  "Seat: seat_01 (inbox-clerk).\nKey: bWludGVkLWJ5LXRoZS1lbmdpbmU")
             (sch/fire-text seat nil "bWludGVkLWJ5LXRoZS1lbmdpbmU"))))

    (testing "no key leaves the line out, for a Routine that holds a
              standing key of its own"
      (is (= (sch/fire-text seat nil) (sch/fire-text seat nil nil))))

    (testing "and a seat with no instructions carries no key at all"
      (is (= "Walk the three messages."
             (sch/fire-text bare "Walk the three messages."
                            "bWludGVkLWJ5LXRoZS1lbmdpbmU"))))

    (testing "and nothing is ever cut — both are somebody's whole words"
      (let [long-prose (apply str (repeat 2000 "x"))
            long-instructions (apply str (repeat 2000 "y"))
            out (sch/fire-text {:id "seat_03"
                                :data {:name "long-winded"
                                       :instructions long-instructions}}
                               long-prose)]
        (is (str/includes? out long-instructions))
        (is (str/includes? out long-prose))))))

(deftest a-seat-with-no-link-of-its-own-fires-through-its-chair
  (let [cn :sched-chair
        _ (drain! cn)
        chair (model! "claude-chair-5")
        _ (link-model! chair a-chair-url a-chair-token)
        copies-before (count (sch/copies *fake*))
        seat-id (seat! "chair-clerk" 3600 [chair]
                       {:instructions the-instructions})]
    (drain! cn)

    (testing "the schedule stands, and the engine pushed no copy of its own —
              the Routine it fires through was made by hand, one row over"
      (is (some? (sched-of seat-id)))
      (is (nil? (get-in (sched-of seat-id) [:data :external_id])))
      (is (= copies-before (count (sch/copies *fake*)))))

    (testing "the row carries no link, and the chair's is the one a fire uses"
      (let [row (sched-of seat-id)]
        (is (false? (sch/linked? row)) "nothing of its own")
        (is (true? (sch/linked? *eng* row)) "and everything through the chair")
        (is (= {:fire_url a-chair-url :fire_token a-chair-token}
               (sch/link-of *eng* row)))))

    (testing "a person's fire goes out on the chair's URL and token"
      (fire-seat! seat-id "Walk the three messages.")
      (drain! cn)
      (let [f (last (fires-of a-chair-token))]
        (is (some? f) "the POST reached the chair's Routine")
        (is (= a-chair-url (:fire-url f)))
        (is (str/starts-with? (str (:text f)) the-instructions)
            "and it carries the seat's own instructions")
        (is (str/includes? (str (:text f)) (str "Seat: " seat-id " (chair-clerk).")))
        (is (str/includes? (str (:text f))
                           "<routine-fire-payload>\nWalk the three messages.\n</routine-fire-payload>"))))

    (testing "the transition log carries the prose ALONE — not the
              instructions, and not the token"
      (let [t (last (filter #(= :fire (:action %)) (log-of :seat seat-id)))]
        (is (= "Walk the three messages." (str (get-in t [:inputs :text]))))
        (is (not (str/includes? (pr-str t) the-instructions)))
        (is (not (str/includes? (pr-str t) a-chair-token)))))

    (testing "and the seat's own row is where the fire is stamped"
      (is (= :live (:state (sched-of seat-id))))
      (is (some? (get-in (sched-of seat-id) [:data :last_fired_at]))))

    (testing "a step down the ladder is one restate of held_for, and no
              second Routine"
      (let [cheaper (model! "claude-chair-economy")
            _ (link-model! cheaper a-seat-url a-seat-token)
            row (raw :seat seat-id)]
        (inv/invoke! *eng* :seat (str seat-id) :restate
                     {:charter a-charter
                      :instructions the-instructions
                      :scope a-scope
                      :substitute_drop []
                      :held_for [cheaper]
                      :substitute_for []
                      :standing_ttl_seconds 604800
                      :cadence_seconds 3600
                      :budget_usd_per_week 5M
                      :sitting_budget_tokens 60000
                      :rows_per_firing 20
                      :note "Down a rung: the judgment held over five sittings."}
                     {:principal elena
                      :if-match (inv/etag :seat (str seat-id) (:version row))})
        (drain! cn)
        (fire-seat! seat-id "Walk them again.")
        (drain! cn)
        (is (= a-seat-url (:fire-url (last (fires-of a-seat-token))))
            "the next fire goes out on the new chair's Routine")))

    (seat-do! seat-id :retire)))

(deftest a-seat-with-its-own-link-keeps-it
  (let [cn :sched-own-link
        _ (drain! cn)
        chair (model! "claude-chair-shared")
        _ (link-model! chair a-chair-url a-chair-token)
        seat-id (seat! "own-routine-clerk" 3600 [chair])
        _ (drain! cn)
        own-token "rk-test-own-0123456789abcdef"]
    (link-schedule! (:id (sched-of seat-id)) a-seat-url own-token)

    (testing "the row's own link wins over the chair's"
      (is (= {:fire_url a-seat-url :fire_token own-token}
             (sch/link-of *eng* (sched-of seat-id)))))

    (testing "and the fire goes out on it"
      (fire-seat! seat-id "Only this seat's Routine.")
      (drain! cn)
      (let [f (last (fires-of own-token))]
        (is (some? f))
        (is (= a-seat-url (:fire-url f)))
        (is (= "Only this seat's Routine." (:text f))
            "a seat with no instructions fires the prose, as it always did")))

    (seat-do! seat-id :retire)))

(defn- seeded [from]
  (store/with-tx (:storage *eng*)
    (fn [tx] (store/query-rows (:storage *eng*) tx :runner_link
                               {:seeded_from from} {:limit 10}))))

(defn- runners-of [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx] (sch/runners-of-row
              (store/load-row (:storage *eng*) tx kind (str id) {})))))

(deftest the-boot-seeds-one-runner-link-from-each-own-link
  (let [cn :sched-seed-links
        _ (drain! cn)
        chair (model! "claude-chair-seed")
        _ (link-model! chair a-chair-url a-chair-token)
        linked (seat! "seed-linked-clerk" 3600 [chair])
        bare (seat! "seed-bare-clerk" 3600 [chair])
        _ (drain! cn)
        own-token "rk-test-seed-0123456789abcdef"
        linked-sched (:id (sched-of linked))
        bare-sched (:id (sched-of bare))
        from-model (str "model:" chair)
        from-linked (str "schedule:" linked-sched)
        from-bare (str "schedule:" bare-sched)
        set-chair (model! "claude-chair-seed-set")
        _ (link-model! set-chair a-chair-url a-chair-token)
        person-link (str (:id (:row (inv/create! *eng* :runner_link
                                                 {:provider "claude_routine"
                                                  :fire_url a-seat-url
                                                  :fire_token a-seat-token}
                                                 {:principal elena}))))]
    (link-schedule! linked-sched a-seat-url own-token)
    (inv/invoke! *eng* :model (str set-chair) :set_runners
                 {:runners [person-link]} {:principal elena})
    (rl/ensure-seeded-links! *eng*)

    (testing "the model and the linked schedule each get exactly one"
      (let [[m & more] (seeded from-model)]
        (is (some? m))
        (is (empty? more))
        (is (= "claude_routine" (name (get-in m [:data :provider]))))
        (is (= a-chair-url (get-in m [:data :fire_url])))
        (is (= a-chair-token (get-in m [:data :fire_token]))))
      (let [[s & more] (seeded from-linked)]
        (is (some? s))
        (is (empty? more))
        (is (= a-seat-url (get-in s [:data :fire_url])))
        (is (= own-token (get-in s [:data :fire_token])))))

    (testing "a schedule without its own link gets none"
      (is (empty? (seeded from-bare))))

    (testing "each source with no runners names exactly its seeded link"
      (is (= [(str (:id (first (seeded from-model))))] (runners-of :model chair)))
      (is (= [(str (:id (first (seeded from-linked))))]
             (runners-of :schedule linked-sched)))
      (is (nil? (runners-of :schedule bare-sched))))

    (testing "a second boot adds none"
      (rl/ensure-seeded-links! *eng*)
      (is (= 1 (count (seeded from-model))))
      (is (= 1 (count (seeded from-linked))))
      (is (= [(str (:id (first (seeded from-model))))] (runners-of :model chair)))
      (is (= [(str (:id (first (seeded from-linked))))]
             (runners-of :schedule linked-sched))))

    (testing "a list a person set is untouched"
      (is (= 1 (count (seeded (str "model:" set-chair)))))
      (is (= [person-link] (runners-of :model set-chair))))

    (testing "a fire goes through the seeded link"
      (fire-seat! linked "Walk the seeded link.")
      (drain! cn)
      (is (= (str (:id (first (seeded from-linked))))
             (get-in (sched-of linked) [:data :last_runner]))))

    (testing "the sources keep their own links"
      (is (= {:fire_url a-seat-url :fire_token own-token}
             (sch/link-of *eng* (sched-of linked)))))

    (testing "the token is never readable on the new row"
      (let [rdef (get (inv/resources *eng*) :runner_link)
            row (inv/decode-row rdef (first (seeded from-linked)))
            env (render/envelope rdef row {:principal elena
                                           :now ((:now-fn *eng*))})]
        (is (not (contains? (get env "data") "fire_token")))
        (is (not (str/includes? (pr-str env) own-token)))))

    (seat-do! linked :retire)
    (seat-do! bare :retire)))

(def ^:private mayor (t/principal {:id "mayor" :type :agent :display "Mayor"}))

(deftest link-like-copies-a-link-without-a-credential-crossing
  (let [cn :sched-link-like
        _ (drain! cn)
        chair (model! "claude-chair-like")
        _ (link-model! chair a-chair-url a-chair-token)
        linked-id (seat! "linked-by-hand" 3600 [chair])
        new-id (seat! "linked-like" 3600 [chair])
        _ (drain! cn)
        own-token "rk-test-like-0123456789abcdef"
        source (:id (sched-of linked-id))
        target (:id (sched-of new-id))]
    (link-schedule! source a-seat-url own-token)

    (testing "a row with no link of its own, whose model has none either, cannot be copied"
      (let [bare-id (seat! "linked-like-bare" 3600 [(model! "claude-chair-bare")])]
        (drain! cn)
        (is (thrown? clojure.lang.ExceptionInfo
                     (inv/invoke! *eng* :schedule (str source) :link_like
                                  {:like (str (:id (sched-of bare-id)))} {:principal mayor})))
        (seat-do! bare-id :retire)))

    (testing "nor can a row be linked like itself"
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/invoke! *eng* :schedule (str source) :link_like
                                {:like (str source)} {:principal mayor}))))

    (testing "an agent copies a person's link, and the copy is the link a fire uses"
      (inv/invoke! *eng* :schedule (str target) :link_like
                   {:like (str source)} {:principal mayor})
      (is (= :live (:state (sched-of new-id))))
      (is (= {:fire_url a-seat-url :fire_token own-token}
             (sch/link-of *eng* (sched-of new-id)))
          "the row's own link now, not the chair's"))

    (testing "the log says where the link came from, and never what it was"
      (let [t (last (filter #(= :link_like (:action %)) (log-of :schedule target)))]
        (is (= (str source) (str (get-in t [:inputs :like]))))
        (is (not (str/includes? (pr-str t) own-token)))))

    (testing "and the fire goes out on the copied Routine"
      (fire-seat! new-id "Through the copied link.")
      (drain! cn)
      (let [f (last (fires-of own-token))]
        (is (some? f))
        (is (= a-seat-url (:fire-url f)))))

    (seat-do! linked-id :retire)
    (seat-do! new-id :retire)))

(deftest the-link-form-prefills-the-chairs-url
  ;; waymark ticket 7152184d: the form offered whatever URL the row
  ;; last held, and code-seat was relinked to inbox-clerk's Routine
  (let [cn :sched-link-prefill
        _ (drain! cn)
        chair (model! "claude-chair-prefill")
        _ (link-model! chair a-chair-url a-chair-token)
        held-id (seat! "prefill-held" 3600 [chair])
        bare-id (seat! "prefill-bare" 3600 [])
        _ (drain! cn)
        link (get-in (inv/resources *eng*) [:schedule :actions :link])
        prefill #(render/prefill-values link (sched-of %)
                                        (inv/render-hooks *eng*))]
    (link-schedule! (:id (sched-of held-id)) a-seat-url a-seat-token)
    (link-schedule! (:id (sched-of bare-id)) a-seat-url a-seat-token)

    (testing "a schedule held for a model, naming another Routine, prefills the model's fire_url"
      (is (= {:fire_url a-chair-url} (prefill held-id))))

    (testing "a schedule with no chair prefills its own"
      (is (= {:fire_url a-seat-url} (prefill bare-id))))

    (testing "the token is never prefilled"
      (is (not-any? #(contains? (prefill %) :fire_token) [held-id bare-id]))
      (is (not-any? #(contains? (prefill %) :token) [held-id bare-id])))

    (testing "a door with no :prefill-fn prefills the row's own values"
      (is (= {:model (get-in (sched-of held-id) [:data :model])}
             (render/prefill-values
              (get-in (inv/resources *eng*) [:schedule :actions :restate])
              (sched-of held-id) {}))))

    (seat-do! held-id :retire)
    (seat-do! bare-id :retire)))

(deftest a-broken-schedule-goes-back-to-its-model
  ;; waymark ticket 1cdf9362: no token is pasted on the way back.
  (let [cn :sched-relink-model
        _ (drain! cn)
        chair (model! "claude-chair-relink")
        _ (link-model! chair a-chair-url a-chair-token)
        bare (model! "claude-chair-unlinked")
        by-chair-id (seat! "fires-through-model" 3600 [chair])
        broken-id (seat! "broken-then-relinked" 3600 [chair])
        bare-id (seat! "model-has-no-link" 3600 [bare])
        _ (drain! cn)
        target (:id (sched-of broken-id))]

    (testing "link_like naming a schedule that fires through its model copies the model's link"
      (inv/invoke! *eng* :schedule (str target) :link_like
                   {:like (str (:id (sched-of by-chair-id)))} {:principal mayor})
      (is (= :live (:state (sched-of broken-id))))
      (is (= {:fire_url a-chair-url :fire_token a-chair-token}
             (sch/own-link-of (sched-of broken-id)))))

    (inv/invoke! *eng* :schedule (str target) :unlink nil {:principal elena})
    (is (= :broken (:state (sched-of broken-id))))

    (testing "relink_model brings it back live without a token, on the model's Routine"
      (inv/invoke! *eng* :schedule (str target) :relink_model nil {:principal mayor})
      (is (= :live (:state (sched-of broken-id))))
      (is (nil? (sch/own-link-of (sched-of broken-id))))
      (is (= {:fire_url a-chair-url :fire_token a-chair-token}
             (sch/link-of *eng* (sched-of broken-id))))
      (fire-seat! broken-id "Back on the model's Routine.")
      (drain! cn)
      (is (= a-chair-url (:fire-url (last (fires-of a-chair-token))))))

    (testing "and it is refused when the model has no link"
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/invoke! *eng* :schedule (str (:id (sched-of bare-id)))
                                :relink_model nil {:principal mayor}))))

    (doseq [s [by-chair-id broken-id bare-id]] (seat-do! s :retire))))

;; ── 10 · the pool order through a fire (waymark ticket 4e9b076e) ────
;; runner_links_test drives `fire-pool!` in both orders; here the order
;; reaches it through a seat's fire, so `pool-order-of` is what picks:
;; the order of the row whose runners list the fire took. Every link is
;; fresh, so its use count is only what this test's own fires wrote.

(defn- runner-link! []
  (str (:id (:row (inv/create! *eng* :runner_link
                               {:provider "claude_routine"
                                :fire_url a-seat-url
                                :fire_token a-seat-token}
                               {:principal elena})))))

(defn- set-runners! [kind id body]
  (inv/invoke! *eng* kind (str id) :set_runners body {:principal elena}))

(deftest a-fire-takes-the-order-of-the-row-whose-runners-it-took
  (let [cn :sched-runner-order
        _ (drain! cn)
        chair (model! "claude-chair-order")
        _ (link-model! chair a-chair-url a-chair-token)
        own (seat! "order-own-clerk" 3600 [chair] {:instructions the-instructions})
        bare (seat! "order-bare-clerk" 3600 [chair] {:instructions the-instructions})
        _ (drain! cn)
        [a b c d] (repeatedly 4 runner-link!)
        fire-twice! (fn [seat-id]
                      (vec (repeatedly 2 #(do (fire-seat! seat-id "Walk the pool.")
                                              (drain! cn)
                                              (get-in (sched-of seat-id)
                                                      [:data :last_runner])))))]
    (set-runners! :model chair {:runners [c d] :runner_order "prefer"})

    (testing "a schedule with its own list takes its own order, not the model's"
      (set-runners! :schedule (:id (sched-of own)) {:runners [a b]})
      (is (= [a b] (fire-twice! own))
          "least_used alternates, though the model says prefer"))

    (testing "a schedule that falls back to its model's list takes the model's order"
      (is (nil? (get-in (sched-of bare) [:data :runners])))
      (is (= [c c] (fire-twice! bare))
          "prefer keeps to the first link that may fire"))

    (testing "a schedule's own prefer keeps to its first link"
      (set-runners! :schedule (:id (sched-of own))
                    {:runners [a b] :runner_order "prefer"})
      (is (= "prefer" (get-in (sched-of own) [:data :runner_order])))
      (is (= [a a] (fire-twice! own))))

    (testing "a set_runners without runner_order clears it back to least_used"
      (set-runners! :schedule (:id (sched-of own)) {:runners [a b]})
      (is (nil? (get-in (sched-of own) [:data :runner_order])))
      (is (= [b b] (fire-twice! own))
          "b has run once and a three times, so least_used takes b twice"))

    (doseq [s [own bare]] (seat-do! s :retire))))

(deftest a-broken-runner-is-passed-over-and-the-schedule-holds-only-when-none-is-live
  ;; waymark ticket bb19404d
  (let [cn :sched-broken-runner
        _ (drain! cn)
        chair (model! "claude-chair-broken")
        _ (link-model! chair a-chair-url a-chair-token)
        seat-id (seat! "broken-runner-clerk" 3600 [chair] {:instructions the-instructions})
        _ (drain! cn)
        [a b] (repeatedly 2 runner-link!)
        break! (fn [id]
                 (inv/invoke! *eng* :runner_link id :break
                              {:note "The Routine refused the token."}
                              {:principal sch/system-actor}))]
    (set-runners! :schedule (:id (sched-of seat-id))
                  {:runners [a b] :runner_order "prefer"})
    (break! a)

    (testing "the first runner is broken, so the fire goes through the second"
      (is (not (sch/held? *eng* (sched-of seat-id))))
      (fire-seat! seat-id "Walk the pool.")
      (drain! cn)
      (is (= b (get-in (sched-of seat-id) [:data :last_runner]))))

    (testing "with no runner live, the whole schedule holds"
      (break! b)
      (is (sch/held? *eng* (sched-of seat-id))))

    (seat-do! seat-id :retire)))

(deftest a-seat-whose-only-link-is-a-live-runner-can-be-fired
  ;; waymark ticket 1b6d1073
  (let [cn :sched-runner-only
        _ (drain! cn)
        chair (model! "claude-chair-runner-only")
        seat-id (seat! "runner-only-clerk" 3600 [chair] {:instructions the-instructions})
        bare-id (seat! "no-link-clerk" 3600 [chair] {:instructions the-instructions})
        _ (drain! cn)
        a (runner-link!)
        guard-of (fn [seat-id]
                   (try (fire-seat! seat-id "Walk the pool.") nil
                        (catch clojure.lang.ExceptionInfo e
                          (:guard (ex-data e)))))]
    (set-runners! :schedule (:id (sched-of seat-id)) {:runners [a]})

    (testing "no fire_url on the schedule or the chair, and one live runner: the fire runs"
      (is (nil? (get-in (sched-of seat-id) [:data :fire_url])))
      (is (nil? (guard-of seat-id)))
      (drain! cn)
      (is (= a (get-in (sched-of seat-id) [:data :last_runner]))))

    (testing "with neither a link nor a runner, the fire is still refused"
      (is (= :linked-for-fire (guard-of bare-id))))

    (doseq [s [seat-id bare-id]] (seat-do! s :retire))))

;; ── 11 · the adapter leaves a pool-only schedule alone (waymark ticket 962e0aeb)
;; A schedule whose only way out is a runner pool, its own or its
;; chair's, fires through Routines a person made: push!, pause!,
;; resume! and delete! ask the provider nothing for it, and the row
;; still ends when its seat retires.

(deftest the-adapter-leaves-a-pool-only-schedule-alone
  (let [cn :sched-pool-only
        _ (drain! cn)
        bare (model! "claude-chair-pool-bare")
        pooled (model! "claude-chair-pool")
        _ (set-runners! :model pooled {:runners [(runner-link!)]})
        own (seat! "pool-own-clerk" 3600 [bare])
        _ (drain! cn)
        xid (get-in (sched-of own) [:data :external_id])
        _ (set-runners! :schedule (:id (sched-of own)) {:runners [(runner-link!)]})
        _ (drain! cn)
        creates (:creates (sch/counts *fake*))
        chaired (seat! "pool-chair-clerk" 3600 [pooled])
        _ (drain! cn)
        asked #(dissoc (sch/counts *fake*) :reads)]

    (testing "a seat whose chair holds only a pool gets no copy"
      (is (some? xid) "the own-pool seat was pushed before its pool was set")
      (is (not (sch/linked? *eng* (sched-of chaired))))
      (is (= creates (:creates (sch/counts *fake*))))
      (is (nil? (get-in (sched-of chaired) [:data :external_id]))))

    (let [before (asked)]
      (testing "restate, park and unpark ask the provider nothing"
        (doseq [s [own chaired]]
          (restate-cadence! s 21600)
          (drain! cn)
          (seat-do! s :park)
          (drain! cn)
          (seat-do! s :unpark)
          (drain! cn))
        (is (= before (asked)))
        (is (= "0 * * * *" (:cron (copy-of (sched-of own))))
            "the old copy is not updated")
        (is (true? (:enabled (copy-of (sched-of own)))) "nor paused")
        (is (= :live (:state (sched-of own)))))

      (testing "retire ends both rows and deletes nothing at the provider"
        (doseq [s [own chaired]] (seat-do! s :retire))
        (drain! cn)
        (is (= before (asked)))
        (is (some? (sch/copy *fake* xid)))
        (doseq [s [own chaired]]
          (is (= :ended (:state (sched-of s)))))))))
