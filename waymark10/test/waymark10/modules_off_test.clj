(ns waymark10.modules-off-test
  "The :modules-off setting (ticket f66a2057): a named kind with no
  rows is left out of the engine's registry, a named kind with a row
  stays on, and an empty setting changes nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.engine :as engine]
            [waymark10.server.goals :as goals]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.modules :as modules]
            [waymark10.server.modules-off :as modules-off]
            [waymark10.server.store :as store]
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

;; ── team, attachment, effect_call and job (ticket 40b9bc86) ─────────

(def ^:private more #{:team :attachment :effect_call :job})

(defn- hook-names [off]
  (set (map :hook (modules/hooks nil off))))

(deftest team-attachment-effect-call-and-job-may-be-named
  (is (= more (modules-off/setting {:modules-off more} (constantly nil)))))

(deftest each-off-with-no-rows-boots-green
  (doseq [k more]
    (testing (name k)
      (let [eng (boot (memory/storage) {:modules-off #{k}})]
        (is (not (contains? (kinds eng) k)))
        (is (= #{k} (:modules-off eng)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":modules-off"
                              (inv/create! eng k {} {:principal work-calendar/seed-actor})))))))

(deftest each-named-with-one-row-stays-on
  (doseq [k more]
    (testing (name k)
      (let [storage (memory/storage)
            _ (boot storage {:modules-off #{}})
            _ (store/with-tx storage
                #(store/insert-row! storage % k {:id (str (random-uuid))
                                                 :state :active :data {}}))
            eng (boot storage {:modules-off #{k}})]
        (is (contains? (kinds eng) k))
        (is (= #{} (:modules-off eng)))))))

(deftest an-off-kind-takes-its-surfaces-with-it
  (testing "no attachment: no purge or retention sweeper"
    (is (contains? (hook-names #{}) :attachments-purge))
    (is (not-any? (hook-names #{:attachment}) [:attachments-purge :retention])))
  (testing "no job: no worker and no orphan sweep"
    (is (not-any? (hook-names #{:job}) [:jobs-worker :jobs-orphan-sweeper])))
  (testing "no effect_call: the integrations module keeps its applier"
    (is (contains? (hook-names #{:effect_call}) :integration-applier)))
  (testing "no team: no team confers a role"
    (let [eng (boot (memory/storage) {:modules-off #{:team}})]
      (is (nil? (get (inv/resources eng) :team))))))
