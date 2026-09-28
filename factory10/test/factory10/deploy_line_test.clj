(ns factory10.deploy-line-test
  "A house merge counts as deployed once the policy's deploy check is
  green on a base commit that holds it (ticket 47217098). Judged over
  the REAL GitHub source and an in-memory GitHub, as
  factory10.red-base-test is.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]
            [factory10.main :as main]
            [factory10.sources.forge :as forge]
            [factory10.sources.github :as gh]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private repo "ckopsa/waymark-bench")

(def ^:private a-person (t/principal {:id "colton" :display "Colton"}))

(def ^:private merge-1 "1111111111111111111111111111111111111111")
(def ^:private head-2 "2222222222222222222222222222222222222222")
(def ^:private head-3 "3333333333333333333333333333333333333333")

(defn- world
  "A fake GitHub, the real source over it, and an engine holding one
  active policy for `repo` whose deploy check is `deploy`."
  []
  (let [state (gh/fake-state)
        eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})]
    (inv/create! eng :repo_policy {:repository repo :required_checks ["gate"]
                                   :deploy_check "deploy"}
                 {:principal a-person})
    {:state state :engine eng :source (gh/fake-source state {:repos repo})}))

(defn- the-policy [eng] (first (bench/policies eng :active)))

(defn- deploy-at!
  "The base's head moves to `sha`, and `deploy` finishes on it."
  [{:keys [state]} sha id conclusion]
  (gh/seed-branch! state repo "main" sha)
  (gh/seed-check! state repo sha
                  {:id id :name "deploy" :status "completed" :conclusion conclusion
                   :head_sha sha
                   :html_url (str "https://github.com/" repo "/runs/" id)
                   :details_url (str "https://github.com/" repo
                                     "/actions/runs/900/job/" id)}))

(defn- deploy-pass!
  "The forge pass's deploy read: the base as the source reads it, and
  whether a merge is in it by the source's compare."
  [{:keys [engine source]}]
  (bench/note-deploy! engine (the-policy engine)
                      (forge/forge-base source repo "main")
                      (fn [number sha] (forge/forge-covers? source repo number sha))))

(deftest the-source-says-whether-a-merge-is-in-a-commit
  (let [{:keys [state source]} (world)]
    (gh/seed-pull! state repo {:number 31 :state "closed" :merged true
                               :merge_commit_sha merge-1})
    (gh/seed-pull! state repo {:number 32 :state "open" :merged false})
    (gh/seed-ancestor! state repo head-3 merge-1)
    (is (true? (forge/forge-covers? source repo 31 merge-1)) "the merge commit itself")
    (is (true? (forge/forge-covers? source repo 31 head-3)) "a descendant of it")
    (is (false? (forge/forge-covers? source repo 31 head-2)) "a commit without it")
    (is (false? (forge/forge-covers? source repo 32 head-3)) "a pull request not merged")))

(deftest a-deploy-green-on-a-commit-that-holds-the-merge-takes-it-off-the-waits
  (let [{:keys [state engine] :as w} (world)
        waits #(mapv :number (bench/deploy-waits (the-policy engine)))]
    (gh/seed-pull! state repo {:number 31 :state "closed" :merged true
                               :merge_commit_sha merge-1})
    (gh/seed-ancestor! state repo head-3 merge-1)
    (bench/mark-row! engine :repo_policy (str (:id (the-policy engine)))
                     {:deploy_waits_on ["c-31 t-31 31"]} #{})
    (testing "a green deploy on a commit without the merge leaves it waiting"
      (deploy-at! w head-2 701 "success")
      (deploy-pass! w)
      (is (= [31] (waits)))
      (is (= head-2 (get-in (the-policy engine) [:data :deployed_head]))))
    (testing "a red deploy holds and says so"
      (deploy-at! w head-3 702 "failure")
      (deploy-pass! w)
      (is (= [31] (waits)))
      (is (= "red" (str (get-in (the-policy engine) [:data :deploy_state])))))
    (testing "the deploy green on a descendant of the merge covers it"
      (deploy-at! w head-3 703 "success")
      (deploy-pass! w)
      (is (= [] (waits)))
      (let [p (the-policy engine)]
        (is (= head-3 (get-in p [:data :deployed_head])))
        (is (= "green" (str (get-in p [:data :deploy_state]))))
        (is (some? (get-in p [:data :deployed_at])))))))
