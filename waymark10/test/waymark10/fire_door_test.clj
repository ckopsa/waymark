(ns waymark10.fire-door-test
  "The fire door (docs/spec-seat.md § 12.2, acceptance cases 33 to 35).

  The Routines API is fire-only, so the engine cannot hold a copy of
  the Routine for that provider: a person makes it by hand and pastes
  its fire URL and its token onto the schedule row through `link`,
  and the engine fires it from then on. What this suite proves, in
  the spec's own order:

  - case 33 · `link` moves a broken row to `live` and clears the
    note, a second link replaces the first, `unlink` puts the row
    back to `broken` with the sentence that says no link — and the
    token is in the store and in NO projection: not the envelope,
    not a collection item, and not the transition log's inputs,
    because `link` deliberately does not record (R-12.11).
  - case 34 · `fire` on a linked, active seat is a real transition
    that carries its text, the drain sends exactly one POST with the
    row's URL, token and text, and the provider's answer lands on the
    row as `last_fired_at` and `last_run_url`.
  - case 35 · `fire` on a parked seat, a halted seat, an unlinked
    seat and by a bare agent is refused with one sentence each, and
    the provider is told nothing. The halt is `model_not_held`: a
    line the door cannot judge for itself, and so the one that still
    refuses (waymark-fp62.7.13, and halt-lift-test).
  - the provider's own answers (R-12.20): 429 breaks the row with the
    retry sentence and the next fire that goes out clears it, 400
    pauses it, 401 and 404 break it with their sentences.
  - a replayed fire POSTs once — at-least-once delivery must not
    start a second run of somebody's Routine.
  - a LINKED row is managed by hand (R-12.18): a restate, a park and
    an unpark of its seat call no ScheduleAdapter operation at all.

  Case 36 and case 37 are the wake consumer's, and wait for it.

  Real Postgres (WAYMARK10_TEST_DSN); no network — the fake scheduler
  and the fake fire endpoint stand at the provider."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.collections :as collections]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.schedules :as sch]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["schedules" "seats" "models" "sittings" "definitions" "members" "roles"
   "grants" "approval_requests" "attachments" "subscriptions" "jobs"
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
              eng (engine/engine {:storage st :resources []})]
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

;; ── what a person pastes ────────────────────────────────────────────

(def ^:private a-fire-url
  "https://api.anthropic.com/v1/claude_code/routines/trig_01FAKE/fire")

(def ^:private a-fire-token "rk-test-0123456789abcdef")
(def ^:private a-second-token "rk-test-ABCDEFGHIJKLMNOP")

;; ── writers ─────────────────────────────────────────────────────────

(def ^:private a-scope [{:kind "model" :actions ["retire"]}])
(def ^:private a-charter
  "Decide whether a message asks something of this house.")

(defn- seat! [nm cadence]
  (:id (:row (inv/create! *eng* :seat
                          {:name nm
                           :charter a-charter
                           :scope a-scope
                           :substitute_drop []
                           :held_for []
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
  "The seat's restate states the office whole and carries the fence an
  :edit implies — schedules_test's own helper."
  [seat-id cadence]
  (let [row (raw :seat seat-id)]
    (inv/invoke! *eng* :seat (str seat-id) :restate
                 {:charter a-charter
                  :scope a-scope
                  :substitute_drop []
                  :held_for []
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

(defn- link! [sched-id url token]
  (inv/invoke! *eng* :schedule (str sched-id) :link
               {:fire_url url :token token} {:principal elena}))

(defn- unlink! [sched-id]
  (inv/invoke! *eng* :schedule (str sched-id) :unlink nil {:principal elena}))

(defn- fire-seat!
  ([seat-id] (fire-seat! seat-id nil elena))
  ([seat-id text] (fire-seat! seat-id text elena))
  ([seat-id text principal]
   ;; the door is not idempotent, so every call carries a key — the
   ;; MCP door's posture, minted fresh here as it is there
   (inv/invoke! *eng* :seat (str seat-id) :fire
                (when text {:text text})
                {:principal principal
                 :idempotency-key (str "fire-test:" (random-uuid))})))

;; ── readers ─────────────────────────────────────────────────────────

(defn- sched-of [seat-id] (sch/schedule-for-seat *eng* seat-id))

(defn- log-of [kind id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (store/transitions (:storage *eng*) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- drain!
  "One synchronous drain under a named cursor, seeded before the writes
  it is about — schedules_test's discipline, for the same reason: no
  deftest may replay another's transitions."
  [cname]
  (consumers/drain-consumer! *eng* cname (sch/consumer-fn *eng*)))

(defn- refusal
  "The problem ex-data of a write that was refused, or nil when it was
  served."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(defn- schedule-rdef [] (get (inv/resources *eng*) :schedule))

(defn- envelope-of [schedule-row]
  (let [rdef (schedule-rdef)]
    (render/envelope rdef (inv/decode-row rdef schedule-row)
                     {:now (Instant/now)})))

(defn- linked-seat!
  "A seat, its schedule minted and pushed, and a person's link on it."
  [nm cname]
  (let [seat-id (seat! nm 3600)]
    (drain! cname)
    (link! (:id (sched-of seat-id)) a-fire-url a-fire-token)
    seat-id))

;; ── 1 · case 33: the link, and the token nobody sees ────────────────

(deftest a-link-moves-the-row-to-live-and-the-token-is-never-shown
  (let [cn :fire-link
        _ (drain! cn)
        seat-id (seat! "link-clerk" 3600)
        ;; the same engine with no credential for the provider: the
        ;; push breaks the row, which is where R-12.18 starts
        blind (assoc *eng* :schedule-adapters
                     {:claude_routine (:claude_routine
                                       (sch/from-env (constantly nil)))})]
    (consumers/drain-consumer! blind cn (sch/consumer-fn blind))
    (testing "the row starts broken, with the adapter's own sentence"
      (is (= :broken (:state (sched-of seat-id))))
      (is (some? (get-in (sched-of seat-id) [:data :note]))))

    (testing "a person's link moves it to live and clears the note"
      (link! (:id (sched-of seat-id)) a-fire-url a-fire-token)
      (let [row (sched-of seat-id)]
        (is (= :live (:state row)))
        (is (= a-fire-url (get-in row [:data :fire_url])))
        (is (nil? (get-in row [:data :note])))
        (is (= a-fire-token (get-in row [:data :fire_token]))
            "the store holds the token; nothing else ever will")))

    (testing "a second link replaces the first"
      (link! (:id (sched-of seat-id)) a-fire-url a-second-token)
      (is (= a-second-token (get-in (sched-of seat-id) [:data :fire_token])))
      (is (= :live (:state (sched-of seat-id)))))

    (testing "no projection carries the token — envelope, item or log"
      (let [row (sched-of seat-id)
            env (envelope-of row)
            col (collections/envelope *eng* (schedule-rdef) {}
                                      {:now (Instant/now)})
            log (log-of :schedule (:id row))]
        (is (not (contains? (get env "data") "fire_token")))
        (is (not (contains? (get env "fields") "fire_token")))
        (is (= a-fire-url (get-in env ["data" "fire_url"]))
            "the URL is shown; it carries the Routine's id, not a secret")
        (is (not (str/includes? (wire/write-json env) a-second-token)))
        (is (pos? (count (get-in col ["data" "items"]))))
        (is (not (str/includes? (wire/write-json col) a-second-token)))
        (is (some #(= :link (:action %)) log)
            "the transition says a link was made, by whose hand and when")
        (is (not (str/includes? (pr-str log) a-second-token))
            "and `link` does not record: the credential is not in the inputs")))

    (testing "an agent cannot link"
      (let [p (refusal #(inv/invoke! *eng* :schedule
                                     (str (:id (sched-of seat-id)))
                                     :link {:fire_url a-fire-url
                                            :token a-fire-token}
                                     {:principal clerk}))]
        (is (= :a-person-or-a-delegate (:guard p)))
        (is (= a-second-token (get-in (sched-of seat-id) [:data :fire_token]))
            "and the token that stands is the one the person wrote")))

    (testing "unlink takes both fields off and says there is no link"
      (unlink! (:id (sched-of seat-id)))
      (let [row (sched-of seat-id)]
        (is (= :broken (:state row)))
        (is (nil? (get-in row [:data :fire_url])))
        (is (nil? (get-in row [:data :fire_token])))
        (is (= sch/no-link-note (get-in row [:data :note])))
        (is (str/includes? (get-in row [:data :note]) "No link"))))))

;; ── 2 · case 34: the fire goes out, and the run comes back ──────────

(deftest a-fire-on-a-linked-seat-starts-the-run
  (let [cn :fire-run
        _ (drain! cn)
        seat-id (linked-seat! "fire-clerk" cn)
        text "Walk the three messages that arrived this morning."
        fires-before (count (sch/fires *fire*))
        log-before (count (log-of :seat seat-id))]
    (fire-seat! seat-id text)

    (testing "the fire is a real transition, active to active, carrying its text"
      (let [t (last (filter #(= :fire (:action %)) (log-of :seat seat-id)))]
        (is (some? t))
        (is (= :active (:from-state t)))
        (is (= :active (:to-state t)))
        (is (= text (get-in t [:inputs :text])))))

    (testing "and the seat's transition count moved by one — the ledger counts it"
      (is (= (inc log-before) (count (log-of :seat seat-id)))))

    (testing "the drain sends exactly one POST, with this row's URL, token and text"
      (drain! cn)
      (let [fs (sch/fires *fire*)]
        (is (= (inc fires-before) (count fs)))
        (let [f (last fs)]
          (is (= a-fire-url (:fire-url f)))
          (is (= a-fire-token (:token f)))
          (is (= text (:text f))))))

    (testing "and the provider's answer lands on the schedule row"
      (let [row (sched-of seat-id)]
        (is (= :live (:state row)))
        (is (some? (get-in row [:data :last_fired_at])))
        (is (str/starts-with? (str (get-in row [:data :last_run_url]))
                              "https://claude.ai/code/session_01fake"))
        (is (nil? (get-in row [:data :note])))))

    (testing "a fire with no text sends a fire with no text — that run walks the queue"
      (fire-seat! seat-id)
      (drain! cn)
      (is (nil? (:text (last (sch/fires *fire*))))))))

;; ── 3 · case 35: the four refusals, and the provider hears nothing ──

(deftest a-fire-is-refused-at-every-wall
  (let [cn :fire-walls
        _ (drain! cn)
        seat-id (linked-seat! "wall-clerk" cn)
        bare-id (seat! "unlinked-clerk" 3600)
        _ (drain! cn)
        fires-before (count (sch/fires *fire*))]

    (testing "the seat is parked"
      (seat-do! seat-id :park)
      (let [p (refusal #(fire-seat! seat-id "now please"))]
        (is (= :not-parked (:guard p)))
        (is (= "The seat is parked. Unpark it first." (str (:detail p)))))
      (seat-do! seat-id :unpark))

    ;; THE WALL, NOT THE LINE (waymark-fp62.7.13). The door re-judges
    ;; the week's fuel for itself, so a `budget_reached` line on a seat
    ;; that has spent nothing no longer refuses anything — which is the
    ;; bug this seat would otherwise prove backwards. `model_not_held`
    ;; is a wall the door cannot judge with no sitter in the room, so
    ;; it is the halt that still refuses here; halt-lift-test owns the
    ;; budget line, both ways.
    (testing "the seat is halted — the halt's own sentence, and the way out"
      (seats/seat-halt! *eng* seat-id "model_not_held"
                        "This session declares nothing and wall-clerk is held for 1 model(s) as its full sitter.")
      (let [p (refusal #(fire-seat! seat-id "now please"))]
        (is (= :not-halted (:guard p)))
        (is (str/includes? (str (:detail p)) "is held for"))
        (is (str/includes? (str (:detail p)) "Restate the seat")
            "the sentence names the door that lifts the line"))
      (seats/seat-clear-halt! *eng* seat-id))

    (testing "the schedule has no link"
      (is (nil? (get-in (sched-of bare-id) [:data :fire_url])))
      (let [p (refusal #(fire-seat! bare-id "now please"))]
        (is (= :linked-for-fire (:guard p)))
        (is (= "Link the Routine's fire URL and token to the schedule first."
               (str (:detail p))))))

    (testing "a bare agent, with no person behind it"
      (let [p (refusal #(fire-seat! seat-id "now please" clerk))]
        (is (= :a-person-or-the-engine (:guard p)))))

    (testing "and the provider was told nothing at all"
      (drain! cn)
      (is (= fires-before (count (sch/fires *fire*)))))

    (testing "the person's own fire, on the unwalled seat, is still served"
      (is (some? (fire-seat! seat-id "now please")))
      (drain! cn)
      (is (= (inc fires-before) (count (sch/fires *fire*)))))))

;; ── 4 · the provider's answers (R-12.20) ────────────────────────────

(deftest the-provider-s-answer-lands-on-the-row
  (let [cn :fire-answers
        _ (drain! cn)
        seat-id (linked-seat! "answer-clerk" cn)]
    (try
      (testing "429: the row breaks with the retry sentence"
        (sch/answer! *fire* 429 {:retry-after "30"})
        (fire-seat! seat-id "over the cap")
        (drain! cn)
        (let [row (sched-of seat-id)]
          (is (= :broken (:state row)))
          (is (= "The Routine has no free run. Try again after 30."
                 (get-in row [:data :note])))))

      (testing "and the next fire that goes out clears it"
        (sch/answer! *fire* nil)
        (fire-seat! seat-id "again")
        (drain! cn)
        (let [row (sched-of seat-id)]
          (is (= :live (:state row)))
          (is (nil? (get-in row [:data :note])))))

      (testing "400: the provider says the Routine is paused, and the row pauses"
        (sch/answer! *fire* 400)
        (fire-seat! seat-id "while paused")
        (drain! cn)
        (is (= :paused (:state (sched-of seat-id)))))

      (testing "and a fire that the provider answers heals the paused row"
        (sch/answer! *fire* nil)
        (fire-seat! seat-id "unpaused at the provider")
        (drain! cn)
        (is (= :live (:state (sched-of seat-id)))))

      (testing "401: the Routine refused the token"
        (sch/answer! *fire* 401)
        (fire-seat! seat-id "bad token")
        (drain! cn)
        (let [row (sched-of seat-id)]
          (is (= :broken (:state row)))
          (is (= "The Routine refused the token." (get-in row [:data :note])))))

      (testing "404: no Routine answers the fire URL"
        (sch/answer! *fire* 404)
        (fire-seat! seat-id "gone")
        (drain! cn)
        (let [row (sched-of seat-id)]
          (is (= :broken (:state row)))
          (is (= "No Routine answers the fire URL."
                 (get-in row [:data :note])))))

      (testing "and a refusal never parked the drain — it ran on and stopped"
        (let [n (count (sch/fires *fire*))]
          (drain! cn)
          (is (= n (count (sch/fires *fire*))))
          (is (= :broken (:state (sched-of seat-id))))))
      (finally (sch/answer! *fire* nil)))))

;; ── 5 · the replay POSTs once ───────────────────────────────────────

(deftest a-replayed-fire-starts-one-run
  (let [cn :fire-replay
        twin :fire-replay-twin
        ;; both cursors seed HERE, before this test's writes
        _ (drain! cn)
        _ (drain! twin)
        seat-id (linked-seat! "replay-clerk" cn)]
    (fire-seat! seat-id "exactly once")
    (drain! cn)
    (let [n (count (sch/fires *fire*))
          fired-at (get-in (sched-of seat-id) [:data :last_fired_at])]
      (is (some? fired-at))
      (testing "a second drain over the same transitions posts nothing more"
        (drain! twin)
        (is (= n (count (sch/fires *fire*))))
        (is (= fired-at (get-in (sched-of seat-id) [:data :last_fired_at]))
            "and the stamp did not move"))
      (testing "a NEW fire still goes out — the dedupe is per transition"
        (fire-seat! seat-id "a second wake")
        (drain! cn)
        (is (= (inc n) (count (sch/fires *fire*))))))))

;; ── 6 · a linked row is managed by hand (R-12.18) ───────────────────

(deftest a-linked-row-calls-no-adapter
  (let [cn :fire-bypass
        _ (drain! cn)
        seat-id (linked-seat! "bypass-clerk" cn)
        before (sch/counts *fake*)]
    (restate-cadence! seat-id 7200)
    (seat-do! seat-id :park)
    (seat-do! seat-id :unpark)
    (drain! cn)
    (testing "no ScheduleAdapter operation ran for the linked row"
      (is (= before (sch/counts *fake*))))
    (testing "and the row itself did not move"
      (let [row (sched-of seat-id)]
        (is (= :live (:state row)))
        (is (= a-fire-url (get-in row [:data :fire_url])))))
    (testing "retiring the seat still ends the row, and deletes nothing at the provider"
      (seat-do! seat-id :retire)
      (drain! cn)
      (is (= :ended (:state (sched-of seat-id))))
      (is (= before (sch/counts *fake*))))))
