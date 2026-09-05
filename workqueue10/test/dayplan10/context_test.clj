(ns dayplan10.context-test
  "The template door lived in over the real engine: two active
  contexts of one shape never share a minute. The failure this proves
  against (the :feed/current-block obligation, 2026-09-05): a
  household holding two templates of a day off on the same 19:00–21:00
  could not plan a day at all — day_plan's create mints a span per
  window and span's no-overlap-in-plan refused the second, so the
  PLAN's create failed wholesale. The invariant now lives on context
  (no-overlap-in-shape at create and revise, restores-clear-of-the-
  shape at retire's undo), and materialisation cannot collide by
  construction.

  What the declaration-time world cannot judge is proved here: the
  wall reads the OTHER rows through ctx, so it needs a store — the
  refusal names the template in the way; different shapes overlap
  freely; touching ends are a seam, not a collision; retiring the
  template in the way opens the door, and the retired one cannot come
  back into a window that has since been taken; a revise is judged as
  the template would STAND, input merged over the row, so naming only
  the shapes is still held to the windows kept.

  Every test names its own templates and its own hours, so the order
  the runner picks does not matter.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides.
  Run: cd workqueue10 && clojure -M:test --focus dayplan10.context-test"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dayplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.dev :as dev]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["contexts" "day_plans" "blocks" "spans" "decisions"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *eng* nil)

(def ^:private colton
  (t/principal {:id "colton" :type :human :display "Colton"}))

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources)})]
          (binding [*eng* eng]
            (f)))
        (finally (pg/close! st))))))

;; ── the helpers ─────────────────────────────────────────────────────

(defn- template
  "A context body: one name, its shapes, its windows as [from to]
  pairs."
  [nm shapes & windows]
  {:name nm
   :default_shapes shapes
   :default_spans (mapv (fn [[from to]] {:from from :to to}) windows)
   :default_order 1})

(defn- create! [body]
  (:row (inv/create! *eng* :context body {:principal colton})))

(defn- act!
  "One write through the full invoke algorithm. A fenced door (an
  :edit implies If-Match — revise) gets the live row's own etag, the
  way an honest client that just read the row would supply it;
  invoke-in-tx! asks the fence BEFORE the guards, so a refusal these
  tests read as a guard's must first get past it (day-plan-test's
  spelling)."
  [id action body]
  (let [adef (get-in (inv/resources *eng*) [:context :actions action])
        opts (cond-> {:principal colton}
               (get-in adef [:safety :fence])
               (assoc :if-match (inv/etag :context id (:version (dev/row *eng* :context id)))))]
    (inv/invoke! *eng* :context id action body opts)))

(defn- refusal
  "What a refused write says: {:problem … :guard … :detail …}, nil
  when it went through."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           {:problem (:waymark10/problem d)
            :guard (some-> (:guard d) name)
            :detail (str (:detail d))
            :remedies (:remedies d)}))))

(defn- state-of [id] (:state (dev/row *eng* :context id)))

;; ── § 1 the wall at create ──────────────────────────────────────────

(deftest two-templates-of-one-shape-never-share-a-minute
  (create! (template "CT shop" ["workday" "off"] ["19:00" "21:00"]))
  (testing "a second template of the shape reaching into the CT shop's window is refused, naming it"
    (let [r (refusal #(create! (template "CT evening" ["off"] ["20:00" "22:00"])))]
      (is (= "no-overlap-in-shape" (:guard r)))
      (is (re-find #"20:00–22:00 overlaps CT shop's 19:00–21:00" (:detail r))
          "the refusal names the window in the way and whose it is")
      (is (re-find #"retire or revise CT shop" (:detail r))
          "…and what would make the door available")
      (is (nil? (some #(when (= "CT evening" (get-in % [:data :name])) %)
                      (dev/rows *eng* :context)))
          "nothing landed")))
  (testing "the shape is what is shared: a day off and a workday may occupy the same hour"
    (create! (template "CT rest" ["off"] ["09:00" "12:00"]))
    (is (some? (create! (template "CT workday" ["workday"] ["09:00" "12:00"])))))
  (testing "a window that ends where the CT shop's begins is a seam, not a collision"
    (is (nil? (refusal #(create! (template "CT dinner" ["off"] ["18:00" "19:00"])))))))

;; ── § 2 retire opens the door, restore meets the wall ───────────────

(deftest retiring-the-template-in-the-way-opens-the-door
  (let [errands (create! (template "CT errands" ["workday"] ["06:00" "07:00"]))
        late (template "CT late shift" ["workday"] ["06:30" "07:30"])]
    (is (= "no-overlap-in-shape" (:guard (refusal #(create! late)))))
    (act! (:id errands) :retire nil)
    (is (= :retired (state-of (:id errands))))
    (let [shift (create! late)]
      (is (= :active (:state shift)) "with Errands retired, the window is free")
      (testing "the retired template cannot come back into the window it left"
        (let [r (refusal #(act! (:id errands) :restore nil))]
          (is (= "restores-clear-of-the-shape" (:guard r)))
          (is (re-find #"06:00–07:00 overlaps CT late shift's 06:30–07:30" (:detail r)))
          (is (re-find #"retire or revise CT late shift" (:detail r)))
          (is (= :retired (state-of (:id errands))) "the refusal moved nothing")))
      (testing "moving the late shift clear lets Errands back in"
        (act! (:id shift) :revise {:default_spans [{:from "07:00" :to "08:00"}]})
        (act! (:id errands) :restore nil)
        (is (= :active (state-of (:id errands))))
        (is (= [{:from "07:00" :to "08:00"}]
               (get-in (dev/row *eng* :context (:id shift)) [:data :default_spans]))
            "touching Errands' 07:00 end is fine")))))

;; ── § 3 revise judges the template as it would stand ────────────────

(deftest revise-is-held-to-the-template-it-would-leave-standing
  (let [gym (create! (template "CT gym" ["off"] ["14:00" "15:00"]))
        calls (create! (template "CT calls" ["workday"] ["14:00" "15:00"]))]
    (testing "naming only the shapes is still judged against the windows kept"
      (let [r (refusal #(act! (:id calls) :revise {:default_shapes ["workday" "off"]}))]
        (is (= "no-overlap-in-shape" (:guard r)))
        (is (re-find #"14:00–15:00 overlaps CT gym's 14:00–15:00" (:detail r)))
        (is (= ["workday"] (get-in (dev/row *eng* :context (:id calls)) [:data :default_shapes]))
            "the refusal moved nothing")))
    (testing "a revise that moves the window into another's is refused too"
      (is (= "no-overlap-in-shape"
             (:guard (refusal #(act! (:id gym) :revise
                                     {:default_shapes ["off" "workday"]
                                      :default_spans [{:from "14:30" :to "16:00"}]}))))))
    (testing "and once the Gym has moved, the Calls may serve both shapes"
      (act! (:id gym) :revise {:default_spans [{:from "15:00" :to "16:00"}]})
      (act! (:id calls) :revise {:default_shapes ["workday" "off"]})
      (is (= ["workday" "off"]
             (get-in (dev/row *eng* :context (:id calls)) [:data :default_shapes]))))))
