(ns waymark10.check-all-test
  "The whole-tree declaration gate (ticket c09e2f88): its resources
  are every module's kinds, each once, and they assemble."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [factory10.law-text]
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

;; ── the orgs (ticket 8f46a0a4) ──────────────────────────────────────────────────

(deftest the-touched-orgs-are-read-as-ci-reads-them
  (is (= ["a" "b"]
         (check-all/orgs-in ["orgs/b/src/x.clj" "orgs/a/org.edn" "orgs/b/deps.edn"
                             "orgs/README.md" "docs/orgs/c/x.md"]))))

(deftest an-org-with-errors-fails-the-check-naming-its-file
  (let [n (volatile! nil)
        out (with-out-str
              (vreset! n (check-all/report-orgs [(io/file "../factorykit/fixtures/orgs/broken")])))]
    (is (pos? @n))
    (is (re-find #"✗ orgs/broken \[" out))))

(deftest a-clean-org-passes-the-check
  (let [n (volatile! nil)
        out (with-out-str
              (vreset! n (check-all/report-orgs [(io/file "../orgs/example")])))]
    (is (zero? @n))
    (is (re-find #"orgs/example: conforms" out))))

;; ── the law page (ticket ade71a1e) ─────────────────────────────────────────────

(deftest an-org-whose-law-page-throws-fails-the-check
  (let [n (volatile! nil)
        out (with-redefs [factory10.law-text/law-text
                          (fn [_] (throw (ex-info "no such seat" {})))]
              (with-out-str
                (vreset! n (check-all/report-orgs [(io/file "../orgs/example")]))))]
    (is (= 1 @n))
    (is (re-find #"✗ orgs/example \[law\] law/example: the law page could not render: no such seat" out))))
