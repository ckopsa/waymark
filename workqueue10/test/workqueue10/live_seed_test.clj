(ns workqueue10.live-seed-test
  "A live seed booted from workqueue10 (ticket 3f29e232): a seed marked
  `:live-ok` gets no demo clock and is not asked for FACTORY10=1, and
  its template step fills the factory kit's real `code-seat` template
  on a fresh engine not named `demo-…`. The seed is
  test/waymark10/live-test/seed.edn. In memory.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.live-seed-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as factory]
            [waymark10.dev :as dev]
            [waymark10.server.roles :as roles]
            [waymark10.server.seed :as seed]
            [workqueue10.main :as main]))

(def ^:private demo-seed? @#'main/demo-seed?)
(def ^:private clocked-engine @#'main/clocked-engine)
(def ^:private seed-on-boot! @#'main/seed-on-boot!)

(deftest a-live-seed-is-not-a-demo-seed
  (is (true? (:live-ok (seed/read-seed "live-test"))))
  (is (false? (demo-seed? "live-test")))
  (is (true? (demo-seed? "demo")))
  (is (false? (demo-seed? nil)))
  (is (false? (demo-seed? ""))))

(deftest a-live-seed-gets-no-demo-clock
  (let [opts {:storage ::storage :resources []}]
    (is (= [opts] (clocked-engine "live-test" false vector opts))
        "the engine is built from the opts as they are")))

(deftest a-live-seed-boots-without-factory10
  (let [calls (atom [])]
    (with-redefs [seed/boot! (fn [eng seed-name opts]
                               (swap! calls conj [eng seed-name opts])
                               nil)]
      (seed-on-boot! ::eng "live-test" nil nil))
    (is (= [[::eng "live-test" {:wall-url nil}]] @calls))))

(deftest a-live-seed-fills-the-kits-code-seat-template
  (let [eng (dev/scratch! (factory/resources) {:name "waymark"})
        _ (roles/ensure-factory-roles! eng)
        opts {:params {"repo" "acme/garden"} :wall-url "http://wall.test"}
        result (seed/boot! eng "live-test" opts)
        made (get-in result [:refs :code-seat])
        row (dev/row eng :seat (:id made))]
    (is (true? (:seeded result)))
    (is (= :seat (:kind made)))
    (is (= "code-seat" (get-in row [:data :name])))
    (testing "the repo hole is filled in the instructions"
      (let [text (str (get-in row [:data :instructions]))]
        (is (str/includes? text "one open ticket in acme/garden"))
        (is (not (str/includes? text "{{repo}}")))))
    (testing "a second boot skips it, by the marker"
      (is (false? (:seeded (seed/boot! eng "live-test" opts)))))))
