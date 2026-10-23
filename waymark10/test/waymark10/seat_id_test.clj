(ns waymark10.seat-id-test
  "members/seat-id (spec-seat.md R-22.5): one table of principals read
  by the function, and a grep of the engine's source proving no
  `seat:` prefix test is left outside it. Needs no database."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [waymark10.server.members :as members]))

(deftest seat-id-reads-a-seat-principal
  (doseq [[principal want] [["seat:01HZ" "01HZ"]
                            ["seat:a7006f1e-175b-4b82-9d78-afc71af2d55b"
                             "a7006f1e-175b-4b82-9d78-afc71af2d55b"]
                            ["colton" nil]
                            ["subscription:42" nil]
                            ["seat:" nil]
                            ["" nil]
                            [nil nil]]]
    (is (= want (members/seat-id principal)) (pr-str principal))))

(def ^:private prefix-test
  "A line that tests for the prefix by hand: a starts-with? on it, or a
  regex that opens with it."
  #"starts-with\?.*\"seat:\"|#\"seat:")

(defn- engine-sources
  "Every .clj file under the engine's source root, found beside
  members.clj on the classpath, and that file itself."
  []
  (let [home (io/file (io/resource "waymark10/server/members.clj"))
        root (.getParentFile (.getParentFile home))]
    [home (filter #(str/ends-with? (.getName ^java.io.File %) ".clj")
                  (file-seq root))]))

(deftest no-seat-prefix-test-outside-seat-id
  (let [[home files] (engine-sources)
        found (for [^java.io.File f files
                    :when (not= home f)
                    [n line] (map-indexed vector (str/split-lines (slurp f)))
                    :when (re-find prefix-test line)]
                (str (.getPath f) ":" (inc n) ": " (str/trim line)))]
    (is (< 10 (count files)) "the engine's source is on the classpath")
    (is (empty? found) (str/join "\n" found))))
