(ns factory10.law-text-test
  "M2f (ticket 59f0879f): the example org's law, as the page a person
  approves, pinned word for word. A change to the renderer or to the
  example's law shows here as a diff a person can read. No database."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [factory10.law-text :refer [law-text]]))

(defn- example-dir
  "orgs/example, found from its seed at <org-dir>/resources/example/seed.edn."
  []
  (.getPath (.getParentFile (.getParentFile (.getParentFile
                                             (io/file (io/resource "example/seed.edn")))))))

(deftest the-example-org's-law-reads-as-the-golden-page
  (is (= (slurp (io/resource "factory10/law_text_example.md"))
         (law-text (example-dir)))))
