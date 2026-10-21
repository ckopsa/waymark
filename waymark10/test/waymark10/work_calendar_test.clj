(ns waymark10.work-calendar-test
  "The work calendar (docs/spec-calendar-time.md §3, build step 2): the
  :calendar hook answers the default row with no ref and the named row
  with one, a ref that names no row is unknown and never an exception,
  a ctx without the hook is unknown, the hook rides the invoke ctx and
  the render hooks, an engine keeps one default, and only a person
  keeps the calendar.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN overrides
  the DSN."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.expr :as expr]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.work-calendar :as work-calendar]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time LocalDate LocalTime ZoneId)))

(def ^:dynamic *eng* nil)

(use-fixtures :each
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (jdbc/execute! tx ["DROP TABLE IF EXISTS work_calendars CASCADE"])))
        (binding [*eng* (engine/engine {:storage st :resources []})]
          (f))
        (finally (pg/close! st))))))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk (t/principal {:id "clerk" :type :agent :display "Clerk"}))

(defn- refusal
  "The problem a refused call threw, or nil when the call went through."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d)
             (assoc d :text (str (ex-message e) " " (pr-str d)))
             (throw e))))))

(defn- make! [body principal]
  (:row (inv/create! *eng* :work_calendar body {:principal principal})))

(defn- reread [row]
  ((:read (inv/render-hooks *eng*)) :work_calendar (str (:id row))))

(defn- move! [row action input principal]
  (let [row (reread row)]
    (inv/invoke! *eng* :work_calendar (str (:id row)) action input
                 {:principal principal
                  :if-match (inv/etag :work_calendar (str (:id row)) (:version row))})))

(defn- calendar-of
  "The render hooks' :calendar, fresh: one instance memoizes its reads."
  []
  (:calendar (inv/render-hooks *eng*)))

(def ^:private house
  {:name "Elm Street Landscaping" :zone "America/Denver" :default true})

(deftest the-hook-answers-the-default-and-a-named-calendar
  (let [home (make! house colton)
        crew (make! {:name "Crew" :zone "America/New_York"
                     :workdays ["sat" "mon" "mon"]
                     :hours {:from "07:00" :to "17:00"}}
                    colton)]
    (move! home :add_holiday {:on "2027-05-31" :holiday "Memorial Day"} colton)
    (testing "with no ref, the default row governs"
      (let [cal ((calendar-of))]
        (is (= (str (:id home)) (:id cal)))
        (is (= (:version (reread home)) (:version cal))
            "the version a verdict keeps as evidence")
        (is (= (ZoneId/of "America/Denver") (:zone cal)))
        (is (= #{:mon :tue :wed :thu :fri} (:workdays cal))
            "a calendar that names no workdays works Monday to Friday")
        (is (nil? (:hours cal)) "no hours: the whole day")
        (is (= {(LocalDate/parse "2027-05-31") "Memorial Day"} (:holidays cal)))
        (is (= cal ((calendar-of) nil)) "a nil ref is no ref")))
    (testing "a ref names its own calendar"
      (let [cal ((calendar-of) (str (:id crew)))]
        (is (= "Crew" (:name cal)))
        (is (= (ZoneId/of "America/New_York") (:zone cal)))
        (is (= #{:mon :sat} (:workdays cal)))
        (is (= {:from (LocalTime/of 7 0) :to (LocalTime/of 17 0)} (:hours cal)))
        (is (= {} (:holidays cal)))))
    (testing "a holiday is data: naming the day again renames it, and it can go"
      (move! home :add_holiday {:on "2027-05-31" :holiday "Decoration Day"} colton)
      (is (= {(LocalDate/parse "2027-05-31") "Decoration Day"}
             (:holidays ((calendar-of)))))
      (move! home :remove_holiday {:on "2027-05-31"} colton)
      (is (= {} (:holidays ((calendar-of))))))
    (testing "a ref that names no row is unknown, never an exception"
      (is (nil? ((calendar-of) "00000000-0000-0000-0000-000000000000")))
      (is (nil? ((calendar-of) "not-a-row")))
      (is (expr/unknown? (work-calendar/governing {:calendar (calendar-of)}
                                                  "not-a-row"))))
    (testing "a retired calendar governs nothing, and is the default no more"
      (move! crew :retire {} colton)
      (is (nil? ((calendar-of) (str (:id crew)))))
      (move! home :retire {} colton)
      (is (expr/unknown? (work-calendar/governing {:calendar (calendar-of)}))))))

(deftest without-the-hook-the-calendar-is-unknown
  (testing "a ctx with no :calendar"
    (is (expr/unknown? (work-calendar/governing {})))
    (is (expr/unknown? (work-calendar/governing {} "00000000-0000-0000-0000-000000000000"))))
  (testing "a ctx that cannot read rows gets no hook"
    (is (nil? (work-calendar/hook nil nil))))
  (testing "a hook, and no default row"
    (is (expr/unknown? (work-calendar/governing {:calendar (calendar-of)})))))

(deftest the-hook-rides-the-invoke-ctx
  (let [home (make! house colton)]
    (store/with-tx (:storage *eng*)
      (fn [tx]
        (let [ctx (#'inv/make-ctx *eng* tx :dry-run colton)]
          (is (fn? (:calendar ctx)))
          (is (= (str (:id home)) (:id ((:calendar ctx))))))))))

(deftest an-engine-keeps-one-default
  (let [home (make! house colton)
        crew (make! {:name "Crew" :zone "America/Denver"} colton)]
    (testing "a second default is refused at birth"
      (is (some? (refusal #(make! (assoc house :name "Another") colton)))))
    (testing "and at the door, until the first is cleared"
      (is (some? (refusal #(move! crew :make_default {} colton))))
      (move! home :clear_default {} colton)
      (is (nil? (refusal #(move! crew :make_default {} colton))))
      (is (= (str (:id crew)) (:id ((calendar-of))))))))

(deftest the-calendar-is-a-persons
  (testing "a bare agent makes no calendar"
    (is (some? (refusal #(make! house clerk)))))
  (testing "a zone that names none is refused"
    (is (some? (refusal #(make! (assoc house :zone "Mars/Olympus") colton)))))
  (testing "a day that ends before it starts is refused"
    (is (some? (refusal #(make! (assoc house :hours {:from "17:00" :to "07:00"})
                                colton)))))
  (testing "a bare agent adds no holiday"
    (let [home (make! house colton)]
      (is (some? (refusal #(move! home :add_holiday
                                  {:on "2027-12-25" :holiday "Christmas"}
                                  clerk)))))))
