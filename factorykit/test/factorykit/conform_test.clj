(ns factorykit.conform-test
  "Org conformance (ticket 86f96963): the example org passes clean, and
  a fixture org with a state no seat leaves, a guard with no sentence
  and a seat scope naming an unknown kind earns exactly those three
  findings, keyed the same on every run. No database, no network."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [factorykit.conform :as conform])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest the-example-org-conforms
  (is (= [] (conform/conform "../orgs/example"))))

(deftest the-orgkit-template-conforms-as-a-new-org
  (let [src (io/file "../orgkit/template")
        dir (io/file (str (Files/createTempDirectory "orgkit" (make-array FileAttribute 0)))
                     "myorg")]
    (doseq [^File f (file-seq src)
            :when (.isFile f)
            :let [to (io/file dir (str (.relativize (.toPath src) (.toPath f))))]]
      (io/make-parents to)
      (io/copy f to))
    (is (= [] (filterv #(= :error (:severity %)) (conform/conform (str dir)))))))

(defn- example-with-mayor
  "A copy of orgs/example whose seed holds the org-mayor template,
  filled with `kinds`, as the onboarding seat writes it. → its dir."
  [kinds]
  (let [src (io/file "../orgs/example")
        dir (io/file (str (Files/createTempDirectory "org-mayor" (make-array FileAttribute 0)))
                     "example")
        seed-file (io/file dir "resources" "example" "seed.edn")]
    (doseq [^File f (file-seq src)
            :when (.isFile f)
            :let [to (io/file dir (str (.relativize (.toPath src) (.toPath f))))]]
      (io/make-parents to)
      (io/copy f to))
    (spit seed-file
          (pr-str (-> (edn/read-string (slurp seed-file))
                      (update :domains (partial mapv #(assoc % :ref :example)))
                      (update :seats conj
                              {:template "org-mayor"
                               :params {:org "example" :domain "example"
                                        :domain_id [:ref :example] :owner "owner"
                                        :budget_usd_per_week 8M :kinds kinds :wakes []}}))))
    (str dir)))

(deftest the-example-org-conforms-with-its-mayor-in-the-seed
  (is (= [] (conform/conform (example-with-mayor [{:kind "request" :actions []}
                                                  {:kind "work_order" :actions []}]))))
  (testing "and a mayor naming a kind the org does not serve earns its finding"
    (is (= ["scope/unknown-kind/example-mayor/ticket"]
           (mapv :key (conform/conform (example-with-mayor [{:kind "ticket" :actions []}])))))))

(deftest the-live-report-reads-the-flag-and-the-mayor
  (is (= {:live_flag false} (conform/live-report "../orgs/example")))
  (is (= {:live_flag false
          :mayor {:name "example-mayor" :budget_usd_per_week "8"}}
         (conform/live-report (example-with-mayor [{:kind "request" :actions []}])))))

(deftest a-broken-org-earns-exactly-its-three-findings
  (let [fs (conform/conform "fixtures/orgs/broken")]
    (is (= ["crack/no_exit/gizmo/stuck"
            "sentence/gizmo/start/broken/unsaid"
            "scope/unknown-kind/clerk/gadget"]
           (mapv :key fs)))
    (is (every? #(= :error (:severity %)) fs))
    (is (every? #(and (:class %) (:subject %) (seq (:sentence %)) (seq (:remedy %))) fs))
    (testing "the keys are stable across runs"
      (is (= (mapv :key fs)
             (mapv :key (conform/conform "fixtures/orgs/broken")))))))

(deftest a-seat-above-its-owners-ceiling-earns-one-finding
  (is (= ["scope/outside-owner/clerk/gizmo/finish"]
         (mapv :key (conform/conform "fixtures/orgs/overreach")))))

(deftest an-org-whose-name-is-not-snake-case-earns-a-finding
  (let [dir (io/file (str (Files/createTempDirectory "orgs" (make-array FileAttribute 0)))
                     "bad-name")
        _ (.mkdirs dir)
        [f] (conform/conform (str dir))]
    (is (= "org_name_not_snake_case/bad-name" (:key f)))
    (is (= :error (:severity f)))
    (is (re-find #"bad-name" (:sentence f))))
  (is (not-any? #(= :org_name_not_snake_case (:class %))
                (conform/conform "fixtures/orgs/unmarked"))))

(deftest an-org-with-no-manifest-earns-a-finding-naming-it
  (let [[f] (conform/conform "fixtures/orgs/unmarked")]
    (is (= "manifest/missing/unmarked" (:key f)))
    (is (re-find #"orgs/unmarked/org\.edn" (:sentence f)))))

(deftest a-cast-scope-naming-what-the-engine-does-not-serve-earns-its-findings
  (is (= ["scope/unknown-action/cast/boss/gizmo/begin"
          "scope/unknown-kind/cast/boss/gadget"]
         (mapv :key (conform/conform "fixtures/orgs/miscast")))))
