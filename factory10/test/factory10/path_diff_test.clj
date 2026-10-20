(ns factory10.path-diff-test
  "The forge says whether a directory changed between two heads (ticket
  de085c93), for base_follow's org_dir diff. Judged over the REAL
  GitHub source and an in-memory GitHub, as factory10.deploy-line-test
  is.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.sources.forge :as forge]
            [factory10.sources.github :as gh]))

(def ^:private repo "ckopsa/waymark-bench")

(def ^:private take-1 "1111111111111111111111111111111111111111")
(def ^:private head-2 "2222222222222222222222222222222222222222")
(def ^:private head-3 "3333333333333333333333333333333333333333")

(defn- world []
  (let [state (gh/fake-state)]
    {:state state :source (gh/fake-source state {:repos repo})}))

(deftest the-source-says-whether-a-directory-changed
  (let [{:keys [state source]} (world)]
    (gh/seed-ancestor! state repo head-2 take-1)
    (gh/seed-ancestor! state repo head-3 take-1)
    (gh/seed-diff! state repo take-1 head-2 ["orgs/acme/plan.edn" "README.md"])
    (gh/seed-diff! state repo take-1 head-3
                   ["orgs/acme-two/plan.edn"
                    {:filename "docs/moved.md" :previous_filename "orgs/beta/moved.md"}])
    (testing "a file under the directory changed"
      (is (true? (forge/forge-path-changed? source repo take-1 head-2 "orgs/acme")))
      (is (true? (forge/forge-path-changed? source repo take-1 head-2 "orgs/acme/"))))
    (testing "no file under the directory changed"
      (is (false? (forge/forge-path-changed? source repo take-1 head-2 "orgs/beta")))
      (is (false? (forge/forge-path-changed? source repo take-1 head-3 "orgs/acme"))
          "a sibling sharing the prefix is not under it"))
    (testing "a rename counts at its old name"
      (is (true? (forge/forge-path-changed? source repo take-1 head-3 "orgs/beta"))))
    (testing "the same head changes nothing"
      (is (false? (forge/forge-path-changed? source repo head-2 head-2 "orgs/acme"))))
    (testing "an empty path is the whole tree"
      (is (true? (forge/forge-path-changed? source repo take-1 head-2 ""))))))

(deftest a-full-file-list-cannot-say-the-path-is-untouched
  (let [{:keys [state source]} (world)]
    (gh/seed-diff! state repo take-1 head-2
                   (map #(str "elsewhere/" % ".txt") (range 300)))
    (is (true? (forge/forge-path-changed? source repo take-1 head-2 "orgs/acme")))))

(deftest under-path
  (is (forge/under-path? "orgs/acme" "orgs/acme"))
  (is (forge/under-path? "./orgs/acme" "orgs/acme/x/y.edn"))
  (is (not (forge/under-path? "orgs/acme" "orgs/acmeX/y.edn")))
  (is (not (forge/under-path? "orgs/acme" nil))))
