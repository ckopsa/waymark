(ns waymark10.check-all-test
  "The whole-tree declaration gate (ticket c09e2f88): its resources
  are every module's kinds, each once, and they assemble."
  (:require [clojure.test :refer [deftest is]]
            [waymark10.check-all :as check-all]
            [waymark10.checks :as checks]
            [waymark10.server.engine :as engine]))

(deftest the-union-holds-every-modules-kinds-once
  (let [kinds (map (comp name :kind) (check-all/resources))]
    (is (apply distinct? kinds))
    (is (some #{"ticket"} kinds) "factory10's kinds are in")
    (is (some #{"person"} kinds) "workqueue10's kinds are in")))

(deftest the-union-assembles
  (is (map? (engine/full-registry (check-all/resources)))))

(deftest the-union-has-no-unwaived-dead-end
  ;; what `make check-queue` now fails on, read over the same kinds: a
  ;; guard that refuses in words carries :remedies, :open or a waiver.
  (let [reg (engine/full-registry (check-all/resources))
        cen (checks/census (vals (:kinds reg)))]
    (is (= []
           (mapv (fn [{:keys [kind door guard]}] [kind door (:name guard)])
                 (:dead-ends cen))))))
