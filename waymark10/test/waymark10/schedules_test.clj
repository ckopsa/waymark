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

  Real Postgres (WAYMARK10_TEST_DSN); no network — the fake scheduler
  stands at the provider."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.schema :as schema]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as sch]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["schedules" "seats" "models" "sittings" "definitions" "members" "roles"
   "grants" "approval_requests" "attachments" "subscriptions" "jobs"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_cursors"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *fake* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [fake (sch/fake-scheduler)
              eng (engine/engine {:storage st :resources []})]
          (binding [*eng* (assoc eng :schedule-adapters {:claude_routine fake})
                    *fake* fake]
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

(defn- seat! [nm cadence held]
  (:id (:row (inv/create! *eng* :seat
                          {:name nm
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
                          {:principal elena}))))

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
             :wake_pending :wake_fired_at}
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
      (doseq [a [:link :unlink]]
        (is (not-any? :hide (get-in rd [:actions a :guards]))
            (str a " is a person's door")))
      (is (= #{:fire_url :token}
             (set (schema/entry-keys (:input (get-in rd [:actions :link])))))))
    (testing "the deviations are on the record"
      (is (seq (:deviations rd)))
      (is (some #(str/includes? % "mirror") (:deviations rd))))))
