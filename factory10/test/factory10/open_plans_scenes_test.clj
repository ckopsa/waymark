(ns factory10.open-plans-scenes-test
  "The open-plans-scenes job's script (ticket d9937a28), run against two
  org directories copied from orgs/example: the one whose week names a
  hyphenated row the person creates checks ready, and the one whose week
  calls a kind the org does not serve is refused, its plan named. No
  network: the directories are written under a temp dir.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.open-plans-scenes :as open-plans]))

(defn- example-dir
  "orgs/example, found from its seed at <org-dir>/resources/example/seed.edn."
  []
  (.getParentFile (.getParentFile (.getParentFile
                                   (io/file (io/resource "example/seed.edn"))))))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "open-plans" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- org-with-week!
  "A copy of orgs/example at `root`/`label`/example whose week.edn is
  `week`, as its path."
  [root label week]
  (let [^java.io.File from (example-dir)
        to (io/file root label "example")]
    (doseq [^java.io.File f (file-seq from) :when (.isFile f)]
      (let [dest (io/file to (str (.relativize (.toPath from) (.toPath f))))]
        (io/make-parents dest)
        (io/copy f dest)))
    (spit (io/file to "resources" "example" "week.edn") (pr-str week))
    (.getPath to)))

(def ^:private a-week-the-person-creates-a-hyphenated-row-in
  "The shape of the dress plan's week that broke #1996: a person's
  create named with a hyphen, named again by a later `:row` and `$row`."
  {:day-one "2026-10-26"
   :days [{:day 1 :card "Monday. The owner files a birch request."
           :calls [{:as :person :kind "request" :action "create" :name "birch-2025"
                    :input {:title "Trim the birch"}}
                   {:as :mayor :kind "request" :action "accept" :row "birch-2025"}
                   {:as :mayor :kind "work_order" :action "create"
                    :input {:title "Trim the birch" :request_id {"$row" "birch-2025"}}}]}]})

(def ^:private a-week-calling-a-kind-not-served
  "A week whose mayor creates an invoice, a kind orgs/example does not
  declare."
  {:day-one "2026-10-26"
   :days [{:day 1 :card "Monday. The mayor sends an invoice."
           :calls [{:as :mayor :kind "invoice" :action "create"
                    :input {:title "Front hedge"}}]}]})

(def ^:private sha "5e469c6166edf6a3f28ae2e6bdb88b775025f14d")

(deftest the-script-refuses-the-bad-week-and-passes-the-other
  (let [root (temp-dir)
        good (org-with-week! root "good" a-week-the-person-creates-a-hyphenated-row-in)
        bad (org-with-week! root "bad" a-week-calling-a-kind-not-served)]
    (testing "the hyphenated week checks ready"
      (is (nil? (open-plans/refusal good sha "2026-10-26"))))
    (testing "the week calling a kind not served is refused with a sentence"
      (let [why (open-plans/refusal bad sha "2026-10-26")]
        (is (string? why))
        (is (not (str/blank? why)))))
    (testing "the report names the refused plan and its sentence, and fails"
      (let [{:keys [ok lines]} (open-plans/report [(str "#1=" sha "=" good)
                                                   (str "#2=" sha "=" bad)]
                                                  "2026-10-26")]
        (is (false? ok))
        (is (= "ready: #1 (example at 5e469c6)" (first lines)))
        (is (str/starts-with? (second lines) "refused: #2 (example at 5e469c6): "))))
    (testing "the good plan alone passes"
      (is (true? (:ok (open-plans/report [(str "#1=" sha "=" good)] "2026-10-26")))))))

(defn- org-that-does-not-load!
  "An org at `root`/`label`/unloadable whose resources throw a definition
  error, as #1671's shop_rehearsal did, as its path."
  [root label]
  (let [to (io/file root label "unloadable")
        seed (io/file to "resources" "unloadable" "seed.edn")
        src (io/file to "src" "unloadable" "org.clj")]
    (io/make-parents seed)
    (io/make-parents src)
    (spit seed (pr-str {:org "unloadable"}))
    (spit src (str "(ns unloadable.org)\n"
                   "(defn resources []\n"
                   "  (throw (ex-info \"definition error: kiln_batch [house-undeclared] made\" {})))\n"))
    (.getPath to)))

(deftest an-org-that-does-not-load-is-skipped-not-failed
  (let [root (temp-dir)
        unloadable (org-that-does-not-load! root "unloadable")
        bad (org-with-week! root "bad" a-week-calling-a-kind-not-served)]
    (testing "the org that does not load is skipped with its first error line"
      (is (= {:skipped "definition error: kiln_batch [house-undeclared] made"}
             (open-plans/verdict unloadable sha "2026-10-26"))))
    (testing "beside a refused scene, the first is skipped and the job fails on the second"
      (let [{:keys [ok lines]} (open-plans/report [(str "#1=" sha "=" unloadable)
                                                   (str "#2=" sha "=" bad)]
                                                  "2026-10-26")]
        (is (false? ok))
        (is (= "skipped: does not load at its head: #1 (unloadable at 5e469c6): definition error: kiln_batch [house-undeclared] made"
               (first lines)))
        (is (str/starts-with? (second lines) "refused: #2 (example at 5e469c6): "))
        (is (= "summary: 1 checked, 1 skipped, 1 failed" (last lines)))))
    (testing "the org that does not load alone passes"
      (let [{:keys [ok lines]} (open-plans/report [(str "#1=" sha "=" unloadable)] "2026-10-26")]
        (is (true? ok))
        (is (= "summary: 0 checked, 1 skipped, 0 failed" (last lines)))))))

(deftest with-no-open-plan-the-script-passes-and-says-so
  (is (= {:ok true :lines [open-plans/none-open]} (open-plans/report [] "2026-10-26"))))
