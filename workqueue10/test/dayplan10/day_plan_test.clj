(ns dayplan10.day-plan-test
  "The day plan lived in over the real engine (waymark-i89n.2 and .3):
  what the declaration-time world cannot judge — a plan's create
  minting blocks and spans from the active contexts of its shape, a
  reshape replacing only the windows still ahead, and the span doors
  moving real rows against each other: swap exchanging two windows,
  extend sliding the neighbour's start and refusing at zero width,
  split leaving two spans of one block, and current unique across a
  plan.

  The scenarios on span.clj already prove the verdicts (overlap
  refused, a past window does not move, extend refuses the squeeze,
  split refuses a moment outside the window) through the HTTP door in
  the conformance suite; this file proves the EFFECTS.

  The clock is the engine's :now-fn, pinned per test to a moment on
  the plan's own day, and every instant is spelled through
  dayplan10.zone so the assertions hold in whatever zone the
  environment names (CI names none: UTC).

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides.
  Run: cd workqueue10 && clojure -M:test --focus dayplan10.day-plan-test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dayplan10.main :as main]
            [dayplan10.zone :as zone]
            [next.jdbc :as jdbc]
            [waymark10.dev :as dev]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time LocalDate LocalTime)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["contexts" "day_plans" "blocks" "spans"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *eng* nil)

(def ^:private today (LocalDate/parse "2026-01-06"))       ; a Tuesday
(def ^:private saturday (LocalDate/parse "2026-01-10"))
(def ^:private yesterday (LocalDate/parse "2026-01-05"))

(defn- at
  "A clock time on a date, in the household zone."
  ([hh mm] (at today hh mm))
  ([date hh mm] (zone/at date (LocalTime/of (int hh) (int mm)))))

(def ^:private clock
  "The engine's clock; each test pins it to the moment it needs."
  (atom (at 10 30)))

(def ^:private colton
  (t/principal {:id "colton" :type :person :display "Colton"}))

(defn- create! [kind body]
  (:row (inv/create! *eng* kind body {:principal colton})))

(defn- act! [kind id action body]
  (inv/invoke! *eng* kind id action body {:principal colton}))

(defn- refusal
  "What a refused write says: {:problem … :guard …}, nil when it went
  through."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           {:problem (:waymark10/problem d)
            :guard (some-> (:guard d) name)
            :detail (:detail d)}))))

(defn- row [kind id] (dev/row *eng* kind id))

(defn- blocks-of [plan-id]
  (->> (dev/rows *eng* :block)
       (filter #(= plan-id (get-in % [:data :plan_id])))
       (sort-by :created-at)))

(defn- spans-of
  ([plan-id] (spans-of plan-id nil))
  ([plan-id block-id]
   (->> (dev/rows *eng* :span)
        (filter #(and (= plan-id (get-in % [:data :plan_id]))
                      (or (nil? block-id) (= block-id (get-in % [:data :block_id])))))
        (sort-by #(get-in % [:data :starts_at])))))

(defn- window [s] [(get-in s [:data :starts_at]) (get-in s [:data :ends_at])])

(defn- block-named [plan-id nm]
  (some #(when (= nm (get-in % [:data :context_name])) %) (blocks-of plan-id)))

(def ^:private member-seq (atom 0))
(defn- fresh-member [] (str "member-" (swap! member-seq inc)))

(defn- plan! [date]
  (create! :day_plan {:date (str date) :member (fresh-member)}))

;; the house's templates: a workday is the Workday then the Shop; a
;; day off is Rest then the Shop
(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources)
                                  :now-fn (fn [] @clock)})]
          (binding [*eng* eng]
            (create! :context {:name "Workday" :default_shapes ["workday"]
                               :default_spans [{:from "09:00" :to "12:00"}
                                               {:from "13:00" :to "17:00"}]
                               :default_order 1
                               :seam "That's the workday."})
            (create! :context {:name "Shop" :default_shapes ["workday" "off"]
                               :default_spans [{:from "19:00" :to "21:00"}]
                               :default_order 2})
            (create! :context {:name "Rest" :default_shapes ["off"]
                               :default_spans [{:from "09:00" :to "12:00"}]
                               :default_order 1})
            (create! :context {:name "Retired shape" :default_shapes ["workday"]
                               :default_spans [{:from "07:00" :to "08:00"}]
                               :default_order 0})
            (let [retired (some #(when (= "Retired shape" (get-in % [:data :name])) %)
                                (dev/rows eng :context))]
              (act! :context (:id retired) :retire nil))
            (f)))
        (finally (pg/close! st))))))

;; ── § 1 materialisation ─────────────────────────────────────────────

(deftest a-workday-plan-mints-the-workday-shapes-blocks-and-spans
  (reset! clock (at 8 0))
  (let [plan (plan! today)
        pid (:id plan)
        blocks (blocks-of pid)]
    (is (= "workday" (get-in plan [:data :shape]))
        "Tuesday's shape is the clock's pick")
    (is (= ["Workday" "Shop"] (mapv #(get-in % [:data :context_name]) blocks))
        "one block per active context of the shape, in default_order — the retired one absent")
    (is (every? #(= today (get-in % [:data :date])) blocks)
        "a block takes its date from its plan")
    (is (= "That's the workday." (get-in (first blocks) [:data :context_seam]))
        "the context's seam sentence rides the block")
    (is (= [[(at 9 0) (at 12 0)] [(at 13 0) (at 17 0)] [(at 19 0) (at 21 0)]]
           (mapv window (spans-of pid)))
        "the windows are the templates' clock times on the plan's date, in the household zone")
    (is (every? #(= :planned (:state %)) (spans-of pid)))
    (is (= "Workday · 2026-01-06" (get-in (first (spans-of pid)) [:data :block_name]))
        "a span carries its block's label")))

(deftest a-weekend-defaults-to-a-day-off
  (reset! clock (at saturday 8 0))
  (let [plan (plan! saturday)]
    (is (= "off" (get-in plan [:data :shape])))
    (is (= ["Rest" "Shop"] (mapv #(get-in % [:data :context_name]) (blocks-of (:id plan)))))))

(deftest a-named-shape-overrides-the-weekday
  (reset! clock (at 8 0))
  (let [plan (create! :day_plan {:date (str today) :member (fresh-member) :shape "off"})]
    (is (= "off" (get-in plan [:data :shape])))
    (is (= ["Rest" "Shop"] (mapv #(get-in % [:data :context_name]) (blocks-of (:id plan)))))))

(deftest one-plan-per-member-and-date
  (reset! clock (at 8 0))
  (let [member (fresh-member)]
    (create! :day_plan {:date (str today) :member member})
    (is (= :unique-conflict
           (:problem (refusal #(create! :day_plan {:date (str today) :member member}))))
        "the second plan for the same member and date is the index's 409")))

;; ── § 2 reshape ─────────────────────────────────────────────────────

(deftest reshape-to-off-replaces-only-the-spans-still-ahead
  ;; half past ten on a workday: the morning has begun, the afternoon
  ;; and the evening have not
  (reset! clock (at 10 30))
  (let [plan (plan! today)
        pid (:id plan)
        workday (block-named pid "Workday")
        shop (block-named pid "Shop")]
    (act! :day_plan pid :reshape {:shape "off"})
    (is (= "off" (get-in (row :day_plan pid) [:data :shape])))
    (testing "the morning that began stands; the afternoon ahead is let go"
      (is (= [[[(at 9 0) (at 12 0)] :planned]
              [[(at 13 0) (at 17 0)] :skipped]]
             (mapv (juxt window :state) (spans-of pid (:id workday))))))
    (testing "a block whose context left the shape stays when a window of it began"
      (is (= :planned (:state (row :block (:id workday))))))
    (testing "a surviving context's ahead window is re-minted from the new shape"
      (is (= [[[(at 19 0) (at 21 0)] :planned]
              [[(at 19 0) (at 21 0)] :skipped]]
             (sort-by second (mapv (juxt window :state) (spans-of pid (:id shop))))))
      (is (= :planned (:state (row :block (:id shop))))))
    (testing "a context the new shape brings arrives — with only the windows still ahead"
      (let [rest (block-named pid "Rest")]
        (is (some? rest))
        (is (empty? (spans-of pid (:id rest)))
            "Rest's nine-to-noon had already begun, so nothing is minted for it")))
    (testing "the day is a draft again"
      (is (= :drafting (:state (row :day_plan pid)))))))

(deftest reshape-before-the-day-begins-lets-the-old-shape-go
  (reset! clock (at 6 0))
  (let [plan (plan! today)
        pid (:id plan)
        workday (block-named pid "Workday")]
    (act! :day_plan pid :set nil)
    (act! :day_plan pid :reshape {:shape "off"})
    (is (= :skipped (:state (row :block (:id workday))))
        "no window of the Workday had begun, so the block is let go")
    (is (every? #(= :skipped (:state %)) (spans-of pid (:id workday))))
    (let [rest (block-named pid "Rest")]
      (is (= [[[(at 9 0) (at 12 0)] :planned]]
             (mapv (juxt window :state) (spans-of pid (:id rest))))
          "Rest arrives with its whole morning, still ahead at six"))))

(deftest reshape-wants-the-other-shape
  (reset! clock (at 6 0))
  (let [plan (plan! today)]
    (is (= "a-different-shape"
           (:guard (refusal #(act! :day_plan (:id plan) :reshape {:shape "workday"})))))))

;; ── § 3 set, replan, close ──────────────────────────────────────────

(deftest replan-opens-a-set-day-while-something-is-ahead
  (reset! clock (at 10 30))
  (let [plan (plan! today)
        pid (:id plan)]
    (act! :day_plan pid :set nil)
    (is (= :set (:state (row :day_plan pid))))
    (act! :day_plan pid :replan nil)
    (is (= :drafting (:state (row :day_plan pid))))))

(deftest a-spent-day-is-not-replanned
  (reset! clock (at 10 30))
  (let [plan (plan! yesterday)
        pid (:id plan)]
    (act! :day_plan pid :set nil)
    (let [r (refusal #(act! :day_plan pid :replan nil))]
      (is (= "something-still-ahead" (:guard r)))
      (is (re-find #"[Cc]lose the day" (str (:detail r)))
          "the refusal names the door that fits"))
    (act! :day_plan pid :close nil)
    (is (= :closed (:state (row :day_plan pid))))
    (is (every? #(= :planned (:state %)) (spans-of pid))
        "closing the day leaves its spans as the record")))

;; ── § 4 the span doors ──────────────────────────────────────────────

(deftest swap-exchanges-two-windows
  (reset! clock (at 8 0))
  (let [plan (plan! today)
        pid (:id plan)
        [morning afternoon] (spans-of pid (:id (block-named pid "Workday")))]
    (act! :span (:id morning) :swap {:with_span_id (:id afternoon)})
    (is (= [(at 13 0) (at 17 0)] (window (row :span (:id morning)))))
    (is (= [(at 9 0) (at 12 0)] (window (row :span (:id afternoon)))))
    (is (= 1 (count (filter #(= (at 9 0) (get-in % [:data :starts_at])) (spans-of pid))))
        "the plan still holds exactly one nine o'clock — an exchange, not a copy")))

(deftest extend-slides-only-the-neighbours-start
  (reset! clock (at 11 0))
  (let [plan (plan! today)
        pid (:id plan)
        [morning afternoon] (spans-of pid (:id (block-named pid "Workday")))]
    (testing "an extension into the gap slides nobody"
      (act! :span (:id morning) :extend {:ends_at (str (at 12 30))})
      (is (= [(at 9 0) (at 12 30)] (window (row :span (:id morning)))))
      (is (= [(at 13 0) (at 17 0)] (window (row :span (:id afternoon))))))
    (testing "an extension into the neighbour slides its start and only its start"
      (act! :span (:id morning) :extend {:ends_at (str (at 13 30))})
      (is (= [(at 9 0) (at 13 30)] (window (row :span (:id morning)))))
      (is (= [(at 13 30) (at 17 0)] (window (row :span (:id afternoon))))
          "the neighbour's start slid; its end did not move"))
    (testing "and it refuses to squeeze the neighbour to nothing"
      (let [r (refusal #(act! :span (:id morning) :extend {:ends_at (str (at 17 0))}))]
        (is (= "neighbour-keeps-some-width" (:guard r)))
        (is (re-find #"no time at all" (str (:detail r))))
        (is (= [(at 13 30) (at 17 0)] (window (row :span (:id afternoon))))
            "the refusal moved nothing")))
    (testing "an earlier end is a move, not an extend"
      (is (= "extends-later"
             (:guard (refusal #(act! :span (:id morning) :extend {:ends_at (str (at 12 0))}))))))))

(deftest split-yields-two-spans-of-one-block
  (reset! clock (at 8 0))
  (let [plan (plan! today)
        pid (:id plan)
        workday (block-named pid "Workday")
        [_ afternoon] (spans-of pid (:id workday))]
    (act! :span (:id afternoon) :split {:at (str (at 14 30)) :gap_minutes 30})
    (let [ws (spans-of pid (:id workday))]
      (is (= [[(at 9 0) (at 12 0)] [(at 13 0) (at 14 30)] [(at 15 0) (at 17 0)]]
             (mapv window ws))
          "the first half ends at the split, the second starts after the gap and runs to the old end")
      (is (every? #(= :planned (:state %)) ws))
      (is (every? #(= (:id workday) (get-in % [:data :block_id])) ws)
          "both halves belong to the one block"))
    (testing "the gap defaults to the lunch hour"
      (let [[morning] (spans-of pid (:id workday))]
        (act! :span (:id morning) :split {:at (str (at 10 0))})
        (is (= [[(at 9 0) (at 10 0)] [(at 11 0) (at 12 0)]]
               (->> (spans-of pid (:id workday)) (take 2) (mapv window))))))))

(deftest current-is-unique-across-a-plan
  (reset! clock (at 10 30))
  (let [plan (plan! today)
        pid (:id plan)
        spans (spans-of pid)]
    (is (= 1 (count (filter #(true? (get-in % [:data :current])) spans)))
        "at half past ten exactly one window contains now")
    (is (= [(at 9 0) (at 12 0)]
           (window (some #(when (get-in % [:data :current]) %) spans))))
    (is (every? #(false? (get-in % [:data :missed])) spans)
        "nothing has ended yet")
    (testing "because no window may overlap another"
      (let [shop (block-named pid "Shop")
            r (refusal #(create! :span {:block_id (:id shop) :plan_id pid
                                        :starts_at (str (at 11 0))
                                        :ends_at (str (at 14 0))}))]
        (is (= "no-overlap-in-plan" (:guard r)))
        (is (re-find #"Workday" (str (:detail r)))
            "the refusal names the span in the way")))
    (testing "a gap is not an overlap"
      (let [shop (block-named pid "Shop")]
        (is (nil? (refusal #(create! :span {:block_id (:id shop) :plan_id pid
                                            :starts_at (str (at 12 0))
                                            :ends_at (str (at 13 0))}))))))))

(deftest a-past-window-does-not-move
  (reset! clock (at 10 30))
  (let [plan (plan! yesterday)
        [morning] (spans-of (:id plan))
        r (refusal #(act! :span (:id morning) :move
                          {:starts_at (str (at yesterday 14 0))
                           :ends_at (str (at yesterday 15 0))}))]
    (is (= "still-ahead" (:guard r)))
    (is (true? (get-in (row :span (:id morning)) [:data :missed]))
        "…and the clock fact says the window is behind us")))

(deftest a-block-the-shape-left-out-is-one-create
  (reset! clock (at 8 0))
  (let [plan (plan! today)
        pid (:id plan)
        rest (some #(when (= "Rest" (get-in % [:data :name])) %) (dev/rows *eng* :context))]
    (testing "a block plus one window, in one call"
      (let [b (create! :block {:plan_id pid :context_id (:id rest)
                               :windows [{:starts_at (str (at 17 30))
                                          :ends_at (str (at 18 30))}]})]
        (is (= today (get-in b [:data :date])) "the date is the plan's")
        (is (nil? (get-in b [:data :windows])) "the windows are spans, not a stored copy")
        (is (= [[(at 17 30) (at 18 30)]] (mapv window (spans-of pid (:id b)))))))
    (testing "a window that overlaps the day refuses the whole block"
      (let [before (count (blocks-of pid))
            r (refusal #(create! :block {:plan_id pid :context_id (:id rest)
                                         :windows [{:starts_at (str (at 16 0))
                                                    :ends_at (str (at 18 0))}]}))]
        (is (= "no-overlap-in-plan" (:guard r)))
        (is (= before (count (blocks-of pid))) "nothing landed")))
    (testing "skipping a block lets its planned spans go"
      (let [shop (block-named pid "Shop")]
        (act! :block (:id shop) :skip nil)
        (is (every? #(= :skipped (:state %)) (spans-of pid (:id shop))))))))
