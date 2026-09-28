(ns workqueue10.migrate-plan-test
  "The migrate dry run's printer: the plan reaches BOTH streams,
  bracketed, so the dispatcher's capture carries it whichever it
  reads; the exit code stays the deploy gate's (0 empty, 1 waiting).
  No database."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [workqueue10.main :as main]))

(defn- printed [lines]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        code (binding [*out* out *err* err] (main/print-plan! lines))]
    {:code code :out (str out) :err (str err)}))

(deftest two-steps-print-to-both-streams
  (let [{:keys [code out err]} (printed ["add column a" "add column b"])]
    (is (= 1 code))
    (doseq [s [out err]]
      (is (str/starts-with? s "=== plan begin ===\n"))
      (is (str/ends-with? s "=== plan end ===\n"))
      (is (str/includes? s "2 migration step(s):"))
      (is (str/includes? s "  add column a\n"))
      (is (str/includes? s "  add column b\n")))))

(deftest empty-plan-prints-and-exits-zero
  (let [{:keys [code out err]} (printed [])]
    (is (= 0 code))
    (doseq [s [out err]]
      (is (str/includes? s "empty plan."))
      (is (str/includes? s "=== plan begin ==="))
      (is (str/includes? s "=== plan end ===")))))

(deftest linger-reads-the-environment
  (testing "tests set 0; unset or unparseable falls back to 15"
    (is (= 0 (main/linger-seconds {"MIGRATE_LINGER_SECONDS" "0"})))
    (is (= 3 (main/linger-seconds {"MIGRATE_LINGER_SECONDS" " 3 "})))
    (is (= 15 (main/linger-seconds {})))
    (is (= 15 (main/linger-seconds {"MIGRATE_LINGER_SECONDS" "soon"})))))
