(ns waymark10.modules-off-test
  "The :modules-off setting (ticket f66a2057): a named kind with no
  rows is left out of the engine's registry, a named kind with a row
  stays on, and an empty setting changes nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.engine :as engine]
            [waymark10.server.goals :as goals]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.modules-off :as modules-off]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.work-calendar :as work-calendar]))

(defn- boot [storage opts]
  (engine/engine (merge {:storage storage :resources [goals/goal]} opts)))

(defn- kinds [eng]
  (set (keys (inv/resources eng))))

(deftest the-setting-is-empty-by-default
  (is (= #{} (modules-off/setting {} (constantly nil))))
  (let [eng (boot (memory/storage) {:modules-off #{}})]
    (is (contains? (kinds eng) :work_calendar))
    (is (contains? (kinds eng) :goal))
    (is (= #{} (:modules-off eng)))))

(deftest the-setting-reads-the-environment
  (is (= #{:work_calendar :goal}
         (modules-off/setting {} {"WAYMARK10_MODULES_OFF" "work_calendar, goal"}))))

(deftest a-name-it-cannot-switch-off-refuses-the-boot
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be switched off"
                        (modules-off/setting {:modules-off #{:task}} (constantly nil)))))

(deftest a-named-kind-with-no-rows-is-off
  (let [eng (boot (memory/storage) {:modules-off #{:work_calendar :goal}})]
    (is (not (contains? (kinds eng) :work_calendar)))
    (is (not (contains? (kinds eng) :goal)))
    (is (= #{:work_calendar :goal} (:modules-off eng)))
    (testing "what read an off kind answers its default"
      (is (nil? (:calendar (inv/render-hooks eng))))
      (is (= 0 (maintainer/default-goals! eng))))))

(deftest a-named-kind-with-a-row-stays-on
  (let [storage (memory/storage)
        first-boot (boot storage {:modules-off #{}})
        _ (inv/create! first-boot :work_calendar (work-calendar/house-default)
                       {:principal work-calendar/seed-actor})
        eng (boot storage {:modules-off #{:work_calendar :goal}})]
    (is (contains? (kinds eng) :work_calendar))
    (is (not (contains? (kinds eng) :goal)))
    (is (= #{:goal} (:modules-off eng)))
    (testing "removing a name brings its kind back"
      (is (contains? (kinds (boot storage {:modules-off #{:work_calendar}})) :goal)))))
