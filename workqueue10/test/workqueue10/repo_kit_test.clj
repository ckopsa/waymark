(ns workqueue10.repo-kit-test
  "selfhost/repo-kit (epic 00680418, T9) against the policy the factory
  seed writes: the seed's repo_policy names the kit's workflow file and
  its dispatch input, the check it requires is the kit's one job, and
  the orientation it names is in the kit. No database, no YAML library:
  the workflow is read by its block indentation.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.repo-kit-test"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.seed :as seed]))

(defn- kit-file
  "A file of the kit, from the suite's directory or the repository's."
  [path]
  (first (filter #(.exists ^java.io.File %)
                 [(io/file "../selfhost/repo-kit" path)
                  (io/file "selfhost/repo-kit" path)])))

(def ^:private policy
  "What the factory seed's repo_policy step creates."
  (:create (first (filter #(= :repo_policy (:kind %))
                          (:steps (seed/read-seed "factory"))))))

(defn- indent [line] (count (re-find #"^ *" line)))

(defn- keys-under
  "The keys directly under `path` in a block-style YAML text. Each step
  takes the block below its key: the lines indented deeper than it."
  [text path]
  (loop [lines (remove #(re-matches #"\s*(#.*)?" %) (str/split-lines text))
         [k & more :as path] path]
    (let [top (when (seq lines) (apply min (map indent lines)))
          head? #(= top (indent %))]
      (if (empty? path)
        (set (keep #(when (head? %) (second (re-find #"^ *([^:\s]+):" %)))
                   lines))
        (recur (->> lines
                    (drop-while #(not (and (head? %)
                                           (str/starts-with? (str/triml %)
                                                             (str k ":")))))
                    rest
                    (take-while #(> (indent %) top)))
               more)))))

(deftest the-kit-matches-the-seeds-policy
  (let [{:keys [workflow input]} (:test policy)
        file (kit-file (str ".github/workflows/" workflow))
        yml (some-> file slurp)]
    (is (some? policy) "the factory seed creates a repo_policy")
    (is (some? file) (str "the kit holds .github/workflows/" workflow))
    (testing "it runs on a pull request and on a dispatch with the input"
      (is (contains? (keys-under yml ["on"]) "pull_request"))
      (is (contains? (keys-under yml ["on" "workflow_dispatch" "inputs"])
                     input))
      (is (str/includes? yml (str "inputs." input))
          "the input reaches the step that runs the tests"))
    (testing "its one job reports the check the policy requires"
      (let [jobs (keys-under yml ["jobs"])]
        (is (= (set (:required_checks policy)) jobs))
        (doseq [job jobs]
          (is (not (contains? (keys-under yml ["jobs" job]) "name"))
              (str job " has no name:, so its check is named by its id")))))
    (testing "the orientation the policy names is in the kit"
      (is (some? (kit-file (:orientation policy)))))))
