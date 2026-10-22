(ns waymark10.ui-paths-test
  "scripts/ui-paths.sh against ci/ui-paths.edn: which diffs run the UI
  walks (ticket 3ca3e028)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- reader-script []
  (some (fn [p] (let [f (io/file p)] (when (.exists f) (.getPath f))))
        ["../scripts/ui-paths.sh"
         "scripts/ui-paths.sh"]))

(deftest the-reader-runs-the-walks-only-for-paths-that-reach-them
  (let [script (reader-script)
        ui (fn [& paths]
             (str/trim (:out (sh/sh "bash" script :in (str (str/join "\n" paths) "\n")))))]
    (is (some? script) "ui-paths.sh is reachable from the test's cwd")
    (when script
      (testing "a ledger-only diff skips the walks"
        (is (= "ui=false" (ui "waymark10/src/waymark10/money.clj"))))
      (testing "a routes file runs them"
        (is (= "ui=true" (ui "waymark10/src/waymark10/money.clj"
                             "waymark10/src/waymark10/server/router.clj"))))
      (testing "the workflow itself runs them"
        (is (= "ui=true" (ui ".github/workflows/tests.yml"))))
      (testing "an empty glob list runs them, as before the list"
        (let [empty-list (doto (java.io.File/createTempFile "ui-paths" ".edn")
                           (.deleteOnExit)
                           (spit "[]\n"))]
          (is (= "ui=true"
                 (str/trim (:out (sh/sh "bash" script (.getPath empty-list)
                                        :in "waymark10/src/waymark10/money.clj\n"))))))))))
