(ns waymark10.module-hooks-test
  "The spine's named hooks (waymark10.modules.hooks): a name not in the
  closed list is refused, an unregistered hook answers its default, and
  a registered one answers in its place."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.modules.hooks :as hooks]))

(defn- with-registry
  "Run `f` with the registry emptied, and put it back after."
  [f]
  (let [registry @#'hooks/registry
        before @registry]
    (reset! registry {})
    (try (f) (finally (reset! registry before)))))

(deftest an-unknown-hook-is-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no hook named :no-such-hook"
                        (hooks/hook :no-such-hook)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no hook named :no-such-hook"
                        (hooks/register-hook! :no-such-hook (constantly 1)))))

(deftest a-hook-answers-its-default-until-registered
  (with-registry
    (fn []
      (testing "no module registered it: the default"
        (is (nil? (hooks/hook :gate-policy-for-repo {} "ckopsa/waymark"))))
      (testing "a module registered it: its fn, with the args"
        (is (= :gate-policy-for-repo
               (hooks/register-hook! :gate-policy-for-repo
                                     (fn [eng repo] [eng repo]))))
        (is (= [{} "ckopsa/waymark"]
               (hooks/hook :gate-policy-for-repo {} "ckopsa/waymark")))))))

(deftest the-gate-path-hooks-answer-their-defaults-without-the-factory
  (with-registry
    (fn []
      (testing ":factory-changes: no change, and no throw"
        (is (nil? (hooks/hook :factory-changes {} nil
                              {:head_branch "bench/t1"} 20))))
      (testing ":factory-ci-runs: no run, and no throw"
        (is (nil? (hooks/hook :factory-ci-runs {} nil "c1" 20)))))))

(deftest the-seat-hooks-answer-their-defaults-without-the-factory
  (with-registry
    (fn []
      (testing ":factory-merged-changes: no merge, and no throw"
        (is (nil? (hooks/hook :factory-merged-changes {} nil "code-seat" 20))))
      (testing ":factory-tickets: no ticket, and no throw"
        (is (nil? (hooks/hook :factory-tickets {} nil {:parent "t1"} 1))))
      (testing ":factory-ticket: no ticket, and no throw"
        (is (nil? (hooks/hook :factory-ticket {} nil "t1")))))))
