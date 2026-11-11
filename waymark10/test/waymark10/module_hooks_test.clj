(ns waymark10.module-hooks-test
  "The spine's named hooks (waymark10.modules.hooks): a name not in the
  closed list is refused, an unregistered hook answers its default, and
  a registered one answers in its place."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.modules.hooks :as hooks]
            [waymark10.server.grants :as grants]
            [waymark10.server.router :as router]))

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

(deftest the-home-answers-without-the-dwelling
  (with-registry
    (fn []
      (let [cairn {:id "cairn" :type :agent}
            home #(with-redefs [grants/member-audience (constantly nil)]
                    (#'router/welcome-home {} cairn))]
        (testing "each dwelling hook: nil, and no throw"
          (is (nil? (hooks/hook :home-self {} "cairn")))
          (is (nil? (hooks/hook :home-journal {} "cairn")))
          (is (nil? (hooks/hook :home-letters {} "cairn"))))
        (testing "no module registered them: home carries no self, journal
                  or letters, and nothing throws"
          (is (nil? (home))))
        (testing "a module registered them: home carries what they answer"
          (hooks/register-hook! :home-self (fn [_eng pid] {:display pid}))
          (hooks/register-hook! :home-journal (fn [_eng _pid] {:recent [{:title "t"}]}))
          (hooks/register-hook! :home-letters (fn [_eng _pid] {:waiting [{:title "l"}]}))
          (let [h (home)]
            (is (= {:display "cairn"} (:self h)))
            (is (= {:recent [{:title "t"}]} (:journal h)))
            (is (= {:waiting [{:title "l"}]} (:letters h)))
            (is (re-find #"1 letter waits" (:note h)))))
        (testing "unregistered again: the default answers"
          (hooks/unregister-hook! :home-self)
          (is (nil? (hooks/hook :home-self {} "cairn"))))))))
