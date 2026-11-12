(ns waymark10.module-hooks-test
  "The spine's named hooks (waymark10.modules.hooks): a name not in the
  closed list is refused, an unregistered hook answers its default, and
  a registered one answers in its place."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.modules :as modules]
            [waymark10.modules.hooks :as hooks]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants.visibility :as grant-vis]
            [waymark10.server.router :as router]
            [waymark10.server.store.memory :as memory]))

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

(deftest the-ticket-filing-hooks-answer-their-defaults-without-the-factory
  (with-registry
    (fn []
      (testing ":factory-ticket-filed: none filed, and no throw"
        (is (nil? (hooks/hook :factory-ticket-filed {} nil "org-crack:delta:1"))))
      (testing ":factory-ticket-transitions: no shelving, and no throw"
        (is (nil? (hooks/hook :factory-ticket-transitions {} nil "t1" 20)))))))

(deftest the-home-answers-without-the-dwelling
  (with-registry
    (fn []
      (let [cairn {:id "cairn" :type :agent}
            home #(with-redefs [grant-vis/member-audience (constantly nil)]
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

(deftest every-hook-belongs-to-a-module-the-table-names
  (is (= (set (keys hooks/defaults)) (set (keys hooks/owners))))
  (is (every? (set (keys hooks/modules)) (vals hooks/owners)))
  (is (every? modules/known (keys hooks/modules))))

(deftest a-module-left-out-answers-the-default-though-registered
  (with-registry
    (fn []
      (hooks/register-hook! :factory-ticket (fn [_eng _tx id] {:id id}))
      (hooks/register-hook! :home-self (fn [_eng pid] {:display pid}))
      (testing "the default assembly loads both: the registered fns"
        (is (= {:id "t1"} (hooks/hook :factory-ticket {} nil "t1")))
        (is (= {:display "cairn"} (hooks/hook :home-self {:modules nil} "cairn"))))
      (testing "one assembled, the other left out"
        (is (= {:id "t1"} (hooks/hook :factory-ticket {:modules [:factory]} nil "t1")))
        (is (nil? (hooks/hook :home-self {:modules [:factory]} "cairn")))))))

(deftest the-engine-boots-with-neither-module
  (with-registry
    (fn []
      (hooks/register-hook! :factory-ticket-filed (fn [_eng _tx k] {:key k}))
      (hooks/register-hook! :home-letters (fn [_eng _pid] {:waiting [{:title "l"}]}))
      (let [eng (engine/engine {:storage (memory/storage) :resources [] :modules []})]
        (testing "it assembles, and leaves both modules out"
          (is (some? eng))
          (is (not-any? #{:factory :dwelling} (map :module (modules/selected (:modules eng))))))
        (testing "each hook answers its default on it, registered or not"
          (doseq [[hook-name args] {:gate-policy-for-repo ["ckopsa/waymark"]
                                    :factory-changes [nil {} 1]
                                    :factory-ci-runs [nil "c1" 1]
                                    :factory-merged-changes [nil "code-seat" 1]
                                    :factory-tickets [nil {} 1]
                                    :factory-ticket [nil "t1"]
                                    :factory-ticket-filed [nil "k"]
                                    :factory-ticket-transitions [nil "t1" 1]
                                    :home-self ["cairn"]
                                    :home-journal ["cairn"]
                                    :home-letters ["cairn"]}]
            (is (nil? (apply hooks/hook hook-name eng args)) (str hook-name))))
        (testing "and the welcome home carries no letters"
          (is (nil? (:letters (with-redefs [grant-vis/member-audience (constantly nil)]
                                (#'router/welcome-home eng {:id "cairn" :type :agent}))))))))))
