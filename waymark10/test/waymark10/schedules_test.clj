(ns waymark10.schedules-test
  "The schedule kind and its adapter (spec-seat.md §12, acceptance
  cases 25 and 26).

  What this suite proves, in the spec's own order: a seat's birth
  mints a schedule in `pending` and stamps its id onto the seat
  (R-12.1, R-12.2); the consumer's first drain makes exactly one copy
  at the provider carrying the seat's name, its cadence as a cron, the
  schedule's model and the fixed pointer prompt, and the row goes
  `live` (R-12.2, R-12.3); a
  `restate` of the cadence pushes again with the new cron and never
  touches the prompt; `park` pauses, `unpark` resumes, `retire` and
  `merge` delete; a row that still holds the dropped mirror fields
  loses them (ticket 28814005); and a provider with no token serves the schedule
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

  Real Postgres (WAYMARK10_TEST_DSN); no network — the fake fire
  endpoint stands at the provider."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.schema :as schema]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.definitions :as defs]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.pull-fires :as pf]
            [waymark10.server.render :as render]
            [waymark10.server.runner-links :as rl]
            [waymark10.server.schedules :as sch]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["schedules" "seats" "models" "sittings" "definitions" "members" "roles"
   "grants" "approval_requests" "attachments" "subscriptions" "jobs"
   "runner_links" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_cursors" "waymark10_drafts" "waymark10_pull_fires"])

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
              eng (engine/engine {:storage st :resources []})]
          (binding [*eng* (assoc eng :fire-adapter fire)
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

(deftest a-row-that-holds-the-mirror-s-fields-loads-with-them-gone
  (let [cn :sched-mirror-fields
        _ (drain! cn)
        seat-id (seat! "mirror-fields-dropped" 3600 [])
        _ (drain! cn)
        before (sched-of seat-id)
        id (str (:id before))
        st (:storage *eng*)]
    (is (some? before) "the seat's birth minted its schedule")
    (store/with-tx st
      (fn [tx]
        (store/update-data! st tx :schedule id
                            (assoc (:data before)
                                   :external_id "trig_01OLDCOPY"
                                   :pushed_at "2026-09-01T00:00:00Z"
                                   :seen_at "2026-09-01T00:05:00Z"
                                   :drift "the copy fires hourly")
                            (:next-flip-at before))))
    (is (pos? (sch/drop-mirror-fields! *eng*)))
    (let [after (raw :schedule id)]
      (is (empty? (select-keys (:data after) sch/mirror-fields))
          "no mirror field is left on the row")
      (is (= (:data before) (:data after))
          "every other field, the runner links among them, is unchanged")
      (is (= (:state before) (:state after)) "the state is unchanged")
      (is (= (:version before) (:version after)) "no transition was written"))
    (is (zero? (sch/drop-mirror-fields! *eng*)) "a second pass writes nothing")))

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
    (is (= #{:seat :provider :model :note
             ;; the fire link (R-12.18), and the damper's mark the wake
             ;; consumer writes (R-12.22)
             :fire_url :fire_token :last_fired_at :last_run_url
             :wake_pending :wake_fired_at :wake_due_at :last_halted_wake
             ;; the first match of a settling wake, for its cap (8f482592)
             :wake_first_at
             ;; the wall the last sit found (ae64b57c)
             :halted
             ;; a throttle's own instant: the pending wake waits for it
             :retry_after
             ;; the transitions a damped wake heard (waymark-fp62.21)
             :wake_heard
             ;; the runner pool and the link the last run took (d16b71bf)
             :runners :last_runner
             ;; the order the pool is tried in (529deb73)
             :runner_order
             ;; the text a fire held by a release grace waits with (afb445d4)
             :wake_text
             ;; every waiting fire's text, and the textless wake that
             ;; waits beside them (58573175)
             :wake_texts :wake_textless
             ;; the head each woken row was last heard at (70aca5e6)
             :wake_seen}
           (set (schema/entry-keys (:schema rd)))))
    (testing "every engine-written door is hidden from a person"
      (doseq [a [:pause :resume :fail :end :fired]]
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
        seat-id (seat! "chair-clerk" 3600 [chair]
                       {:instructions the-instructions})]
    (drain! cn)

    (testing "the schedule stands, and the engine pushed no copy of its own —
              the Routine it fires through was made by hand, one row over"
      (is (some? (sched-of seat-id)))
      (is (nil? (get-in (sched-of seat-id) [:data :external_id]))))

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

(deftest a-pasted-link-supersedes-the-seeded-one
  ;; waymark ticket d55bba92: the boot seed's copy kept every fire on
  ;; the old link after a person pasted a new one
  (let [cn :sched-seed-relink
        _ (drain! cn)
        chair (model! "claude-chair-paste")
        _ (link-model! chair a-chair-url a-chair-token)
        hand-chair (model! "claude-chair-paste-hand")
        _ (link-model! hand-chair a-chair-url a-chair-token)
        clerk (seat! "paste-own-clerk" 3600 [chair])
        bare (seat! "paste-bare-clerk" 3600 [chair])
        _ (drain! cn)
        sched (:id (sched-of clerk))
        old-token "rk-test-relink-old-0123456789ab"
        new-url "https://api.anthropic.com/v1/claude_code/routines/trig_01RELINK/fire"
        new-token "rk-test-relink-new-0123456789ab"
        new-chair-url "https://api.anthropic.com/v1/claude_code/routines/trig_01RECHAIR/fire"
        new-chair-token "rk-test-rechair-new-0123456789a"
        from-sched (str "schedule:" sched)
        from-chair (str "model:" chair)
        from-hand (str "model:" hand-chair)
        state-of #(some-> (first (seeded %)) :state name)
        person-link (str (:id (:row (inv/create! *eng* :runner_link
                                                 {:provider "claude_routine"
                                                  :fire_url a-seat-url
                                                  :fire_token a-seat-token}
                                                 {:principal elena}))))]
    (link-schedule! sched a-seat-url old-token)
    (rl/ensure-seeded-links! *eng*)
    (let [hand-seed (str (:id (first (seeded from-hand))))]
      (is (= [(str (:id (first (seeded from-sched))))] (runners-of :schedule sched)))
      (is (= [(str (:id (first (seeded from-chair))))] (runners-of :model chair)))
      (inv/invoke! *eng* :model (str hand-chair) :set_runners
                   {:runners [hand-seed person-link]} {:principal elena})

      (testing "a schedule's pasted link takes effect at once"
        (link-schedule! sched new-url new-token)
        (drain! cn)
        (is (nil? (runners-of :schedule sched)))
        (is (= "retired" (state-of from-sched)) "the seeded copy is retired")
        (fire-seat! clerk "Fire on the new link.")
        (drain! cn)
        (is (= new-url (:fire-url (last (fires-of new-token)))))
        (is (empty? (fires-of old-token))))

      (testing "a model's pasted link takes effect at once"
        (link-model! chair new-chair-url new-chair-token)
        (drain! cn)
        (is (nil? (runners-of :model chair)))
        (is (= "retired" (state-of from-chair)) "the seeded copy is retired")
        (fire-seat! bare "Fire on the chair's new link.")
        (drain! cn)
        (is (= new-chair-url (:fire-url (last (fires-of new-chair-token))))))

      (testing "a pool a person set by hand survives a link"
        (link-model! hand-chair new-chair-url new-chair-token)
        (drain! cn)
        (is (= [hand-seed person-link] (runners-of :model hand-chair)))
        (is (= "live" (state-of from-hand)) "a seeded link a pool names stays")))

    (seat-do! clerk :retire)
    (seat-do! bare :retire)))

(deftest the-boot-seeds-a-paused-or-broken-schedule-where-it-stands
  (let [cn :sched-seed-states
        _ (drain! cn)
        chair (model! "claude-chair-seed-states")
        _ (link-model! chair a-chair-url a-chair-token)
        paused (seat! "seed-paused-clerk" 3600 [chair])
        broken (seat! "seed-broken-clerk" 3600 [chair])
        _ (drain! cn)
        own-token "rk-test-seed-states-0123456789abcdef"
        paused-sched (:id (sched-of paused))
        broken-sched (:id (sched-of broken))
        state-of #(name (:state (sched-of %)))
        engine! (fn [id action body]
                  (inv/invoke! *eng* :schedule (str id) action body
                               {:principal sch/system-actor}))]
    (link-schedule! paused-sched a-seat-url own-token)
    (link-schedule! broken-sched a-seat-url own-token)
    (drain! cn)
    (engine! paused-sched :pause nil)
    (engine! broken-sched :fail {:note "The provider refused the link."})
    (is (= "paused" (state-of paused)))
    (is (= "broken" (state-of broken)))
    (is (nil? (runners-of :schedule paused-sched)))
    (is (nil? (runners-of :schedule broken-sched)))
    (rl/ensure-seeded-links! *eng*)

    (testing "a paused schedule stays paused and names exactly its seeded link"
      (let [[s & more] (seeded (str "schedule:" paused-sched))]
        (is (some? s))
        (is (empty? more))
        (is (= "paused" (state-of paused)))
        (is (= [(str (:id s))] (runners-of :schedule paused-sched)))))

    (testing "a broken schedule stays broken and names exactly its seeded link"
      (let [[s & more] (seeded (str "schedule:" broken-sched))]
        (is (some? s))
        (is (empty? more))
        (is (= "broken" (state-of broken)))
        (is (= [(str (:id s))] (runners-of :schedule broken-sched)))))

    (testing "a second boot adds none and moves neither"
      (rl/ensure-seeded-links! *eng*)
      (is (= 1 (count (seeded (str "schedule:" paused-sched)))))
      (is (= 1 (count (seeded (str "schedule:" broken-sched)))))
      (is (= "paused" (state-of paused)))
      (is (= "broken" (state-of broken))))

    (seat-do! paused :retire)
    (seat-do! broken :retire)))

(deftest one-seed-door-keeps-each-state-and-the-old-names-answer
  ;; the fold (waymark ticket 99fefa9c): `seed_runners` is one `:to
  ;; :same` door; its per-state copies answer through `:aliases`, and
  ;; the log names `via_alias`
  (let [cn :sched-seed-fold
        _ (drain! cn)
        chair (model! "claude-chair-seed-fold")
        bare (model! "claude-bare-seed-fold")
        _ (link-model! chair a-chair-url a-chair-token)
        paused (seat! "seed-fold-paused-clerk" 3600 [chair])
        broken (seat! "seed-fold-broken-clerk" 3600 [chair])
        pending (seat! "seed-fold-pending-clerk" 3600 [bare])
        _ (drain! cn)
        own-token "rk-test-seed-fold-0123456789abcdef"
        sched-id #(:id (sched-of %))
        state-of #(name (:state (sched-of %)))
        engine! (fn [id action body]
                  (inv/invoke! *eng* :schedule (str id) action body
                               {:principal sch/system-actor}))
        latest (fn [id]
                 (store/with-tx (:storage *eng*)
                   (fn [tx]
                     (first (store/transitions (:storage *eng*) tx
                                               {:kind :schedule :resource-id (str id)}
                                               {:newest-first true :limit 1})))))
        via #(let [a (:actor %)] (or (get a :via_alias) (get a "via_alias")))]
    (link-schedule! (sched-id paused) a-seat-url own-token)
    (link-schedule! (sched-id broken) a-seat-url own-token)
    (drain! cn)
    (engine! (sched-id paused) :pause nil)
    (engine! (sched-id broken) :fail {:note "The provider refused the link."})
    (is (= "paused" (state-of paused)))
    (is (= "broken" (state-of broken)))
    (is (= "pending" (state-of pending)))

    (doseq [[seat state door] [[paused "paused" :seed_runners]
                               [pending "pending" :seed_runners_pending]
                               [broken "broken" :seed_runners_broken]]
            :let [id (sched-id seat)
                  runners [(str "rl-seed-fold-" state)]]]
      (testing (str "a " state " schedule is seeded through " (name door)
                    " and stays " state)
        (engine! id door {:runners runners})
        (is (= state (state-of seat)))
        (is (= runners (runners-of :schedule id)))
        (is (= :seed_runners (:action (latest id))))
        (if (= :seed_runners door)
          (is (nil? (via (latest id))))
          (is (= (name door) (via (latest id)))))))

    (seat-do! paused :retire)
    (seat-do! broken :retire)
    (seat-do! pending :retire)))

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
    ;; the links' pools are written on drain; with `sch/pools-cursor`
    ;; already written, the fire reads them and not the runners
    (drain! cn)

    (testing "no fire_url on the schedule or the chair, and one live runner: the fire runs"
      (is (nil? (get-in (sched-of seat-id) [:data :fire_url])))
      (is (nil? (guard-of seat-id)))
      (drain! cn)
      (is (= a (get-in (sched-of seat-id) [:data :last_runner]))))

    (testing "with neither a link nor a runner, the fire is still refused"
      (is (= :linked-for-fire (guard-of bare-id))))

    (doseq [s [seat-id bare-id]] (seat-do! s :retire))))

(deftest a-pull-fires-answer-lands-on-the-schedule-row
  ;; waymark ticket 5850f18e: runner_links_test shows `answer-fire!`
  ;; hands its answer to `sch/land-pull-answer!`, and a day-old fire to
  ;; `sch/miss!`, by redefining each; here a seat's fire goes out
  ;; through a pull link and both land on the schedule row itself.
  (let [cn :sched-pull-answer
        _ (drain! cn)
        chair (model! "claude-chair-pull")
        _ (link-model! chair a-chair-url a-chair-token)
        seat-id (seat! "pull-clerk" 3600 [chair] {:instructions the-instructions})
        _ (drain! cn)
        st (:storage *eng*)
        p (str (:id (:row (inv/create! *eng* :runner_link
                                       {:provider "localfire" :delivery "pull"
                                        :fire_token a-seat-token}
                                       {:principal elena}))))
        sid (str (:id (sched-of seat-id)))
        fire! (fn []
                (rl/seen! *eng* p (java.time.Instant/now))
                (fire-seat! seat-id "Walk the pool.")
                (drain! cn))
        acted? (fn [action] (some #(= action (:action %)) (log-of :schedule sid)))]
    (set-runners! :schedule sid {:runners [p]})

    (testing "a taken fire's answer stamps fired, last_runner and last_run_url"
      (fire!)
      (let [f (rl/take-fire! *eng* p)]
        (is (= ["schedule" sid] [(:for_kind f) (:for_id f)]))
        (is (some? (rl/answer-fire! *eng* (:id f) {:started "https://run/pull-1"}))))
      (let [row (sched-of seat-id)]
        (is (acted? :fired))
        (is (= p (get-in row [:data :last_runner])))
        (is (= "https://run/pull-1" (get-in row [:data :last_run_url])))
        (is (some? (get-in row [:data :last_fired_at])))))

    (testing "a fire no localfire took within a day is dropped, and the row says so"
      (is (not (acted? :throttle)))
      (fire!)
      (let [later (.plusSeconds (java.time.Instant/now) (+ pf/keep-seconds 60))]
        (is (nil? (rl/take-fire! (assoc *eng* :now-fn (constantly later)) p))))
      (is (empty? (pf/pending st p)) "the missed fire left the queue")
      (is (acted? :throttle))
      (is (str/includes? (str (get-in (sched-of seat-id) [:data :note]))
                         "dropped as missed")))

    (seat-do! seat-id :retire)))

(deftest a-pull-fire-nobody-took-waits-for-its-localfire
  ;; waymark ticket a25950b4: the missed-fire sweep counts a pull fire
  ;; from its take, a second fire queues nothing while one waits, and the
  ;; link's keep drops an untaken fire, revokes its key and arms the wake.
  (let [cn :sched-pull-wait
        _ (drain! cn)
        chair (model! "claude-chair-pull-wait")
        _ (link-model! chair a-chair-url a-chair-token)
        seat-id (seat! "pull-waiter" 3600 [chair] {:instructions the-instructions})
        _ (drain! cn)
        st (:storage *eng*)
        p (str (:id (:row (inv/create! *eng* :runner_link
                                       {:provider "localfire" :delivery "pull"
                                        :fire_token a-seat-token}
                                       {:principal elena}))))
        sid (str (:id (sched-of seat-id)))
        fire! (fn []
                (rl/seen! *eng* p (java.time.Instant/now))
                (fire-seat! seat-id "Walk the pool.")
                (drain! cn))
        at (fn [secs]
             (assoc *eng* :now-fn
                    (constantly (.plusSeconds (java.time.Instant/now) secs))))
        seat-row #(store/with-tx st (fn [tx] (store/load-row st tx :seat (str seat-id) {})))
        missed #(->> (store/with-tx st
                       (fn [tx] (store/query-rows st tx :sitting {:seat (str seat-id)}
                                                  {:limit 50})))
                     (filter (fn [s] (true? (get-in s [:data :missed])))))]
    (set-runners! :schedule sid {:runners [p]})
    (fire!)
    (let [[f :as held] (pf/pending st p)]
      (testing "a fire nobody took for 15 minutes is not missed"
        (is (= 1 (count held)))
        ;; the lease sweep alone: the fire's sitting is what expires
        (#'defs/sweep-sittings! (at 900))
        (is (empty? (missed)) "no never_sat sitting"))

      (testing "a second fire while one waits queues nothing new"
        (fire!)
        (is (= [(:id f)] (mapv :id (pf/pending st p))))
        (is (str/includes? (str (get-in (sched-of seat-id) [:data :note]))
                           "already waits")))

      (testing "taken after that, its key opens the seat, and the sweep counts from the take"
        (let [taken (rl/take-fire! (at 900) p)
              k (second (re-find #"(?m)^Key: (\S+)" (str (:text taken))))]
          (is (= (:id f) (:id taken)))
          (is (seats/fire-key-held? (at 900) (seat-row) k))
          (#'defs/sweep-sittings! (at 1200))
          (is (seats/fire-key-held? (at 1200) (seat-row) k))
          (is (empty? (missed)))
          (is (some? (rl/answer-fire! *eng* (:id taken) {:started "https://run/pull-wait"}))))))

    (testing "a fire nobody took within the keep is dropped, its key revoked and its wake armed"
      (fire!)
      (let [[g] (pf/pending st p)
            entry? (fn [] (some #(= (:id g) (:pull_fire %))
                                (get-in (seat-row) [:data :fire_keys])))]
        (is (some? g))
        (is (entry?))
        (is (nil? (rl/take-fire! (at (+ pf/keep-seconds 60)) p)))
        (is (empty? (pf/pending st p)))
        (is (not (entry?)) "its key was revoked")
        (is (true? (get-in (sched-of seat-id) [:data :wake_pending])))
        (is (empty? (missed)) "no never_sat sitting")))

    (seat-do! seat-id :retire)))

;; ── 10 · which Routine a schedule fires through (ticket 360d207d) ───

(deftest a-schedule-says-which-routine-it-fires-through
  (let [rows {[:seat "s1"] {:id "s1" :data {:held_for ["m1"]}}
              [:model "m1"] {:id "m1" :data {:name "claude-opus-5"
                                              :fire_url a-chair-url}}
              [:seat "s2"] {:id "s2" :data {:held_for ["m2"]}}
              [:model "m2"] {:id "m2" :data {:name "claude-haiku"}}}
        read' (fn [kind id] (get rows [kind (str id)]))
        line (fn [data] (sch/fires-through read' {:data data}))]
    (testing "own link, with its Routine id"
      (is (= "own link · Routine trig_01SEAT"
             (line {:seat "s2" :fire_url a-seat-url}))))
    (testing "an own link that differs from the chair's wins, and says so"
      (is (= "own link · Routine trig_01SEAT"
             (line {:seat "s1" :fire_url a-seat-url}))))
    (testing "the model's link, naming the model"
      (is (= "model claude-opus-5's link · Routine trig_01CHAIR"
             (line {:seat "s1"}))))
    (testing "a runner pool"
      (is (= "runner pool (2 links)"
             (line {:seat "s1" :runners ["r1" "r2"]}))))
    (testing "no link"
      (is (= "no link" (line {:seat "s2"})))
      (is (= "no link" (sch/fires-through nil {:data {:seat "s1"}}))
          "with no read the chair is not consulted"))
    (testing "it rides the schedule's grid fields"
      (is (contains? (render/grid-fields (get (inv/resources *eng*) :schedule)) :fires_through)))))

;; ── 11 · fires_through on an action's own answer (ticket 82589f6e) ──

(deftest an-action-answer-says-which-routine-it-fires-through
  (let [cn :sched-answer-fires-through
        _ (drain! cn)
        chair (model! "claude-chair-answer")
        _ (link-model! chair a-chair-url a-chair-token)
        seat-id (seat! "answer-fires-through" 3600 [chair])
        _ (drain! cn)
        sid (str (:id (sched-of seat-id)))
        opts {:principal mayor :idempotency-key "relink-answer-fires-through"}]
    (testing "the bytes an action answers with read the model in the write's own transaction"
      (inv/invoke! *eng* :schedule sid :relink_model nil opts)
      (let [replay (inv/invoke! *eng* :schedule sid :relink_model nil opts)]
        (is (= :idempotency (:replayed? replay)))
        (is (= "model claude-chair-answer's link · Routine trig_01CHAIR"
               (get-in (wire/read-json (get-in replay [:response :response]))
                       [:data :fires_through])))))
    (testing "a render with no read hooks leaves a :reads? field absent and computes a read-free one"
      (let [plain (assoc-in (get (inv/resources *eng*) :schedule) [:computed :plain]
                            {:schema :string :fn (fn [_row _ctx] "plain")})
            doc (wire/read-json ((:render-fn *eng*) plain (sched-of seat-id)))]
        (is (nil? (get-in doc [:data :fires_through])))
        (is (= "plain" (get-in doc [:data :plain])))))))

;; ── 12 · the one link and its provider's account (ticket b99c8b77) ──
;; A schedule that fires through its own single link has no runner
;; link, but the run still goes out on the claude_routine account:
;; `fire-by-pool-or-link!` asks `rl/account-hold` before the fire and
;; writes the answer through `rl/count-account!` (5c499772). Section 10
;; and runner_links_test reach the provider row only through a pool.

(defn- account
  "The claude_routine provider row, as the store holds it now."
  []
  (let [id (str (:id (rl/provider-row *eng* "claude_routine")))]
    (some->> (raw :runner_provider id)
             (inv/decode-row (get (inv/resources *eng*) :runner_provider)))))

(defn- set-account-cap! [cap]
  (let [row (account)
        id (str (:id row))]
    (inv/invoke! *eng* :runner_provider id :restate {:cap cap}
                 {:principal elena
                  :if-match (inv/etag :runner_provider id (:version row))})))

(defn- open-account-window!
  "The account's window opened now with no runs and no throttle held,
  written through the engine's own door as a landing fire writes it."
  []
  (inv/invoke! *eng* :runner_provider (str (:id (account))) :fired
               {:window_started_at (str (java.time.Instant/now))
                :runs_in_window 0}
               {:principal sch/system-actor}))

(defn- instant [x]
  (some-> x str not-empty java.time.Instant/parse))

(deftest a-single-link-fire-counts-toward-and-waits-on-its-account
  (let [cn :sched-account
        _ (drain! cn)
        _ (rl/ensure-providers! *eng*)
        chair (model! "claude-chair-account")
        tokens (mapv #(str "rk-test-account" % "-0123456789abcdef") (range 4))
        own! (fn [nm token]
               (let [seat-id (seat! nm 3600 [chair])]
                 (drain! cn)
                 (link-schedule! (:id (sched-of seat-id)) a-seat-url token)
                 seat-id))
        [counted capped waited throttled :as seats]
        (mapv own!
              ["account-counted-clerk" "account-capped-clerk"
               "account-waited-clerk" "account-throttled-clerk"]
              tokens)
        fire! (fn [seat-id]
                (fire-seat! seat-id "Out on the account.")
                (drain! cn)
                (sched-of seat-id))]
    (open-account-window!)
    (set-account-cap! nil)
    (try
      (testing "each fire here goes out through the one link, not a pool"
        (is (every? #(nil? (sch/pool-of *eng* (sched-of %) (raw :seat %))) seats)))

      (testing "a started fire counts one run in the account's window"
        (let [row (fire! counted)]
          (is (= 1 (count (fires-of (tokens 0)))))
          (is (some? (get-in row [:data :last_fired_at])))
          (is (= 1 (get-in (account) [:data :runs_in_window])))))

      (testing "a spent cap throttles the schedule until the window closes, and fires nothing"
        (set-account-cap! {:runs 1 :window_seconds 18000})
        (let [closes (some-> (instant (get-in (account) [:data :window_started_at]))
                             (.plusSeconds 18000))
              row (fire! capped)]
          (is (some? closes))
          (is (empty? (fires-of (tokens 1))))
          (is (= closes (instant (get-in row [:data :retry_after]))))
          (is (nil? (get-in row [:data :last_fired_at])))
          (is (= 1 (get-in (account) [:data :runs_in_window]))
              "a held fire is not a run")))

      (testing "a retry_after on the account throttles the schedule with that instant"
        (set-account-cap! nil)
        (let [until (.plusSeconds (.truncatedTo (java.time.Instant/now)
                                                java.time.temporal.ChronoUnit/SECONDS)
                                  600)
              _ (inv/invoke! *eng* :runner_provider (str (:id (account))) :throttle
                             {:retry_after (str until)}
                             {:principal sch/system-actor})
              row (fire! waited)]
          (is (empty? (fires-of (tokens 2))))
          (is (= until (instant (get-in row [:data :retry_after]))))
          (is (str/includes? (str (get-in row [:data :note])) "claude_routine account"))))

      (testing "a 429 that names the account holds the account's row"
        (open-account-window!)
        (is (nil? (rl/account-hold *eng* "claude_routine")))
        (sch/answer! *fire* 429 {:retry-after "30" :body "The account has no free run."})
        (let [before (java.time.Instant/now)
              row (fire! throttled)
              held (instant (get-in (account) [:data :retry_after]))]
          (is (= 1 (count (fires-of (tokens 3)))) "the fire went out and was refused")
          (is (some? held))
          (is (some-> held (.isAfter before)))
          (is (= held (rl/account-hold *eng* "claude_routine")))
          (is (some? (get-in row [:data :retry_after])) "the schedule waits as well")))

      (finally
        (sch/answer! *fire* nil)
        (set-account-cap! nil)
        (open-account-window!)))
    (doseq [s seats] (seat-do! s :retire))))

;; ── 11 · the pools a runner link carries (S1 of spec-seat §22) ──────

(defn- pool-link!
  "A person's runner link, its token named after `tag`. → its id."
  [tag]
  (str (:id (:row (inv/create! *eng* :runner_link
                               {:provider "claude_routine"
                                :fire_url (str "https://api.anthropic.com/v1/claude_code"
                                               "/routines/trig_" tag "/fire")
                                :fire_token (str "rk-test-" tag "-0123456789abcdef")}
                               {:principal elena})))))

(defn- pools-of
  "The ranks at which this link's `pools` names the row, in order."
  [link-id kind id]
  (->> (get-in (raw :runner_link link-id) [:data :pools])
       (filter #(and (= (name kind) (some-> (:kind %) name))
                     (= (str id) (str (:id %)))))
       (mapv #(long (:rank %)))))

(defn- write-under-doors!
  "Restate a row's data with no door and no transition, as a row
  written before the pools were kept holds it."
  [kind id f]
  (let [row (raw kind id)]
    (store/with-tx (:storage *eng*)
      (fn [tx]
        (store/update-data! (:storage *eng*) tx kind (str id)
                            (f (:data row)) (:next-flip-at row))))))

(defn- pools-cursor []
  (store/with-tx (:storage *eng*)
    #(store/cursor-get (:storage *eng*) % sch/pools-cursor)))

(defn- forget-pools-cursor! []
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (jdbc/execute! tx ["DELETE FROM waymark10_cursors WHERE consumer = ?"
                         sch/pools-cursor]))))

(defn- boot-pools!
  "The boot's pool step, and what it said. → [links-written said]."
  []
  (let [w (java.io.StringWriter.)
        n (binding [*err* w] (sch/sync-all-pools! *eng*))]
    [n (str w)]))

(deftest every-runners-writer-restates-the-pools
  (let [cn :sched-pools-writers
        _ (drain! cn)
        chair (model! "claude-chair-pools")
        clerk (seat! "pools-writer-clerk" 3600 [chair])
        like (seat! "pools-like-clerk" 3600 [chair])
        seeded-clerk (seat! "pools-seeded-clerk" 3600 [chair])
        _ (drain! cn)
        sid (:id (sched-of clerk))
        like-id (:id (sched-of like))
        seeded-id (:id (sched-of seeded-clerk))
        [a b c] (mapv pool-link! ["poolswritea" "poolswriteb" "poolswritec"])
        set-runners! (fn [kind id runners]
                       (inv/invoke! *eng* kind (str id) :set_runners
                                    {:runners runners} {:principal elena}))]
    (link-schedule! sid a-seat-url "rk-test-poolswrite-0123456789abcdef")

    (testing "a schedule's set_runners names each link at its place"
      (set-runners! :schedule sid [a b])
      (drain! cn)
      (is (= [0] (pools-of a :schedule sid)))
      (is (= [1] (pools-of b :schedule sid))))

    (testing "and a link it drops loses its entry"
      (set-runners! :schedule sid [b])
      (drain! cn)
      (is (= [] (pools-of a :schedule sid)))
      (is (= [0] (pools-of b :schedule sid))))

    (testing "a model's set_runners is the same, beside the schedule's entries"
      (set-runners! :model chair [c a])
      (drain! cn)
      (is (= [0] (pools-of c :model chair)))
      (is (= [1] (pools-of a :model chair)))
      (is (= [] (pools-of a :schedule sid)))
      (is (= [0] (pools-of b :schedule sid))))

    (testing "link_like copies the list, and the copy names its links"
      (inv/invoke! *eng* :schedule (str like-id) :link_like
                   {:like (str sid)} {:principal mayor})
      (drain! cn)
      (is (= [0] (pools-of b :schedule like-id)))
      (is (= [0] (pools-of b :schedule sid))))

    (testing "the boot seed's list names its link, and a person's link that drops it takes it off"
      (link-schedule! seeded-id a-seat-url "rk-test-poolsseeded-0123456789abcdef")
      (rl/ensure-seeded-links! *eng*)
      (drain! cn)
      (let [link (str (:id (first (seeded (str "schedule:" seeded-id)))))]
        (is (= [link] (runners-of :schedule seeded-id)))
        (is (= [0] (pools-of link :schedule seeded-id)))
        (link-schedule! seeded-id a-seat-url "rk-test-poolsrelinked-0123456789abcdef")
        (drain! cn)
        (is (nil? (runners-of :schedule seeded-id)))
        (is (= [] (pools-of link :schedule seeded-id)))))

    (testing "a schedule's end takes its entries off"
      (seat-do! clerk :retire)
      (seat-do! like :retire)
      (drain! cn)
      (drain! cn)
      (is (= [] (pools-of b :schedule sid)))
      (is (= [] (pools-of b :schedule like-id)))
      (is (= [1] (pools-of a :model chair)) "the model's list still names it"))

    (seat-do! seeded-clerk :retire)))

(deftest the-boot-pool-step-agrees-once-and-then-writes-nothing
  (let [cn :sched-pools-boot
        _ (drain! cn)
        clerk (seat! "pools-boot-clerk" 3600 [])
        odd-clerk (seat! "pools-boot-odd" 3600 [])
        _ (drain! cn)
        sid (:id (sched-of clerk))
        odd (:id (sched-of odd-clerk))
        [a b c] (mapv pool-link! ["poolsboota" "poolsbootb" "poolsbootc"])]
    (link-schedule! sid a-seat-url "rk-test-poolsboot-0123456789abcdef")
    ;; the lists are written under the doors, so no consumer restated
    ;; them: what a row written before S1 holds. `odd`'s one link holds
    ;; a pool entry no `set_pools` accepts, so it cannot be made to agree.
    (write-under-doors! :schedule sid #(assoc % :runners [a b]))
    (write-under-doors! :runner_link c
                        #(assoc % :pools [{:kind "seat" :id "no-such-pool" :rank 0}]))
    (write-under-doors! :schedule odd #(assoc % :runners [c]))
    (forget-pools-cursor!)

    (testing "a row that cannot agree leaves no cursor, and the boot says which"
      (let [[_ said] (boot-pools!)]
        (is (nil? (pools-cursor)))
        (is (str/includes? said (str "schedule " odd " names runner links")))))

    (testing "the rows that could agree were restated all the same, a row with runners and a fire_url among them"
      (is (some? (get-in (raw :schedule sid) [:data :fire_url])))
      (is (= [0] (pools-of a :schedule sid)))
      (is (= [1] (pools-of b :schedule sid))))

    (testing "once every row agrees, the boot writes the cursor"
      (write-under-doors! :runner_link c #(dissoc % :pools))
      (let [[written said] (boot-pools!)]
        (is (pos? written))
        (is (= [0] (pools-of c :schedule odd)))
        (is (not (str/includes? said "do not name it back")))
        (is (some? (pools-cursor)))))

    (testing "a second boot writes nothing, even where a list moved under it"
      (write-under-doors! :schedule sid #(assoc % :runners [b]))
      (is (zero? (first (boot-pools!))))
      (is (= [0] (pools-of a :schedule sid))))

    (seat-do! clerk :retire)
    (seat-do! odd-clerk :retire)))

;; ── 12 · fires read the links whose pools name them (S1b) ─────────────

(defn- with-pools-cursor!
  "Run `f` with `sch/pools-cursor` written (`on?`) or forgotten, and
  leave it as it was found."
  [on? f]
  (let [had (pools-cursor)
        put! #(if %
                (store/with-tx (:storage *eng*)
                  (fn [tx] (store/cursor-set! (:storage *eng*) tx sch/pools-cursor 1)))
                (forget-pools-cursor!))]
    (put! on?)
    (try (f) (finally (put! (some? had))))))

(deftest a-pool-is-read-off-the-links-whose-pools-name-it
  (let [cn :sched-pools-read
        _ (drain! cn)
        chair (model! "claude-chair-pools-read")
        own (seat! "pools-read-own" 3600 [chair])
        chaired (seat! "pools-read-chaired" 3600 [chair])
        dead (seat! "pools-read-dead" 3600 [])
        _ (drain! cn)
        [a b c d] (mapv pool-link! ["poolsreada" "poolsreadb" "poolsreadc" "poolsreadd"])
        set-runners! (fn [kind id runners]
                       (inv/invoke! *eng* kind (str id) :set_runners
                                    {:runners runners} {:principal elena}))
        pools (fn [] (mapv #(sch/pool-of *eng* (sched-of %) (raw :seat %))
                           [own chaired dead]))
        want [[b a] [c a] [d]]]
    (set-runners! :schedule (:id (sched-of own)) [b a])
    (set-runners! :model chair [c a])
    (set-runners! :schedule (:id (sched-of dead)) [d])
    (inv/invoke! *eng* :runner_link d :break
                 {:note "The Routine refused the token."}
                 {:principal sch/system-actor})
    (drain! cn)

    (testing "with no cursor, the pool is each row's runners"
      (with-pools-cursor! false #(is (= want (pools)))))

    (with-pools-cursor! true
      (fn []
        (testing "with the cursor, the pools give the same ids in the same order: the schedule's, the chair's, and one all broken"
          (is (= want (pools)))
          (is (= [c a] (mapv (comp str :id) (sch/links-pooled-by *eng* :model chair)))))

        (testing "a pool whose every link broke is held, not empty"
          (is (sch/held? *eng* (sched-of dead) (raw :seat dead))))

        (testing "the pool is the links', not the row's list"
          (write-under-doors! :model chair #(assoc % :runners []))
          (is (= [c a] (sch/pool-of *eng* (sched-of chaired) (raw :seat chaired)))))

        (testing "fires_through renders the pool off the links"
          (let [hooks (inv/render-hooks *eng*)]
            (is (= "runner pool (2 links)"
                   (sch/fires-through (:read hooks) (sched-of chaired) hooks)))))))

    (doseq [s [own chaired dead]] (seat-do! s :retire))))

;; ── 13 · a person pools a link from the link itself (S2a) ────────────

(defn- pool-do!
  "A person's `join_pool` or `leave_pool` on the link `link-id`."
  [link-id action kind id & [rank]]
  (inv/invoke! *eng* :runner_link link-id action
               (cond-> {:kind (name kind) :id (str id)} rank (assoc :rank rank))
               {:principal elena}))

(deftest a-person-pools-a-link-and-the-runners-follow
  (let [cn :sched-join-pool
        _ (drain! cn)
        chair (model! "claude-chair-join-pool")
        clerk (seat! "join-pool-clerk" 3600 [chair])
        ended (seat! "join-pool-ended" 3600 [chair])
        broken (seat! "join-pool-broken" 3600 [chair] {:instructions the-instructions})
        _ (drain! cn)
        sid (:id (sched-of clerk))
        eid (:id (sched-of ended))
        bid (:id (sched-of broken))
        [a b c] (mapv pool-link! ["joinpoola" "joinpoolb" "joinpoolc"])
        settle! #(dotimes [_ 3] (drain! cn))
        agree? (fn [kind id]
                 (= (or (runners-of kind id) [])
                    (mapv (comp str :id) (sch/links-pooled-by *eng* kind id))))
        sitter (t/principal {:id "join-pool-sitter" :type :agent :display "Sitter"})]

    (testing "join_pool with no rank puts the link last, in a schedule's pool and a model's"
      (pool-do! a :join_pool :schedule sid)
      (pool-do! b :join_pool :schedule sid)
      (pool-do! a :join_pool :model chair)
      (settle!)
      (is (= [a b] (runners-of :schedule sid)))
      (is (= [a] (runners-of :model chair)))
      (is (= [0] (pools-of a :schedule sid)))
      (is (= [1] (pools-of b :schedule sid)))
      (is (agree? :schedule sid))
      (is (agree? :model chair)))

    (testing "a rank at an existing entry shifts the later ones down"
      (pool-do! c :join_pool :schedule sid 1)
      (settle!)
      (is (= [a c b] (runners-of :schedule sid)))
      (is (= [1] (pools-of c :schedule sid)))
      (is (= [2] (pools-of b :schedule sid)))
      (is (agree? :schedule sid)))

    (testing "leave_pool takes it out, and the list closes up"
      (pool-do! a :leave_pool :schedule sid)
      (pool-do! a :leave_pool :model chair)
      (settle!)
      (is (= [c b] (runners-of :schedule sid)))
      (is (nil? (runners-of :model chair)))
      (is (= [] (pools-of a :schedule sid)))
      (is (= [] (pools-of a :model chair)))
      (is (agree? :schedule sid))
      (is (agree? :model chair)))

    (testing "join_pool naming an ended schedule is refused, and so is a seat's hand"
      (seat-do! ended :retire)
      (drain! cn)
      (is (= "ended" (name (:state (raw :schedule eid)))))
      (is (thrown? clojure.lang.ExceptionInfo (pool-do! b :join_pool :schedule eid)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/invoke! *eng* :runner_link b :join_pool
                                {:kind "model" :id (str chair)} {:principal sitter})))
      (is (= [] (pools-of b :schedule eid)))
      (is (= [] (pools-of b :model chair))))

    (testing "a link made with pools is pooled, and the broken schedule it names fires through it"
      (link-schedule! bid a-seat-url "rk-test-joinpoolbroken-0123456789abcdef")
      (inv/invoke! *eng* :schedule (str bid) :unlink nil {:principal elena})
      (drain! cn)
      (is (= :broken (:state (sched-of broken))))
      (let [d (str (:id (:row (inv/create! *eng* :runner_link
                                           {:provider "claude_routine"
                                            :fire_url (str "https://api.anthropic.com/v1/claude_code"
                                                           "/routines/trig_joinpoold/fire")
                                            :fire_token "rk-test-joinpoold-0123456789abcdef"
                                            :pools [{:kind "schedule" :id (str bid)}]}
                                           {:principal elena}))))]
        (settle!)
        (is (= [d] (runners-of :schedule bid)))
        (is (= [0] (pools-of d :schedule bid)))
        (is (not= :broken (:state (sched-of broken))))
        (fire-seat! broken "Through the new link.")
        (drain! cn)
        (is (= d (get-in (sched-of broken) [:data :last_runner])))))

    (doseq [s [clerk broken]] (seat-do! s :retire))))

(deftest a-paused-schedule-stays-paused-when-a-link-joins-or-leaves-its-pool
  (let [cn :sched-follow-paused
        _ (drain! cn)
        chair (model! "claude-chair-follow-paused")
        clerk (seat! "follow-paused-clerk" 3600 [chair])
        _ (drain! cn)
        sid (:id (sched-of clerk))
        [a b] (mapv pool-link! ["followpauseda" "followpausedb"])
        settle! #(dotimes [_ 3] (drain! cn))
        state-of #(name (:state (sched-of clerk)))]
    ;; a person's restate makes the row live; the pause holds it there
    (inv/invoke! *eng* :schedule (str sid) :set_runners {:runners [a]} {:principal elena})
    (settle!)
    (is (= "live" (state-of)))
    (inv/invoke! *eng* :schedule (str sid) :pause nil {:principal sch/system-actor})
    (is (= "paused" (state-of)))

    (testing "join_pool restates the runners and the row stays paused"
      (pool-do! b :join_pool :schedule sid)
      (settle!)
      (is (= [a b] (runners-of :schedule sid)))
      (is (= "paused" (state-of))))

    (testing "leave_pool the same"
      (pool-do! a :leave_pool :schedule sid)
      (settle!)
      (is (= [b] (runners-of :schedule sid)))
      (is (= "paused" (state-of))))

    (testing "follow_runners from a person's grant is refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/invoke! *eng* :schedule (str sid) :follow_runners
                                {:runners [a]} {:principal elena})))
      (is (= [b] (runners-of :schedule sid))))

    (seat-do! clerk :retire)))
