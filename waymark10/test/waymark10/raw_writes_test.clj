(ns waymark10.raw-writes-test
  "The raw-write check (docs/spec-leases.md §4): the tree passes, a
  planted raw write fails, a reason outside the set fails, a known
  exception holds only its own form, and `store/maintain!` itself
  refuses a reason outside the set."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [waymark10.raw-writes :as raw-writes]
            [waymark10.server.store :as store])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- planted
  "The problems of a temporary source root holding one file at `path`."
  [path text]
  (let [root (.toFile (Files/createTempDirectory "raw-writes" (make-array FileAttribute 0)))
        f (io/file root path)]
    (io/make-parents f)
    (spit f text)
    (raw-writes/problems root)))

(deftest the-tree-passes
  (is (= [] (raw-writes/problems))))

(deftest a-planted-raw-write-fails
  (let [ps (planted "server/planted.clj"
                    (str "(ns planted)\n\n(defn plant! [st tx]\n"
                         "  (store/update-data! st tx :seat \"x\" {} nil))\n"))]
    (is (some #(str/starts-with? % "server/planted.clj:4 plant! writes raw") ps))
    (is (some #(str/includes? % "is listed as raw but writes nothing raw") ps)
        "an exception whose site is gone is a problem too")))

(deftest raw-is-the-stores-own
  (let [ps (planted "server/store/planted.clj"
                    "(defn plant! [st tx]\n  (store/update-data! st tx :seat \"x\" {} nil))\n")]
    (is (not-any? #(str/includes? % "planted.clj") ps))))

(deftest a-reason-outside-the-set-fails
  (let [ps (planted "server/planted.clj"
                    (str "(defn plant! [st tx]\n"
                         "  (store/maintain! :whim st tx :seat \"x\" {} nil)\n"
                         "  (store/maintain! :stamp st tx :seat \"x\" {} nil))\n"))]
    (is (some #(str/starts-with? % "server/planted.clj:2 plant! names :whim") ps))
    (is (not-any? #(str/starts-with? % "server/planted.clj:3") ps))))

(deftest a-known-exception-holds-only-its-own-form
  (let [ps (planted "server/seats.clj"
                    (str "(defn- claim-rows! [st tx]\n"
                         "  (store/update-data! st tx :sitting \"x\" {} nil))\n"
                         "(defn- elsewhere! [st tx]\n"
                         "  (store/update-data! st tx :sitting \"x\" {} nil))\n"))]
    (is (not-any? #(str/includes? % "claim-rows! writes raw") ps))
    (is (some #(str/starts-with? % "server/seats.clj:4 elsewhere! writes raw") ps))))

(deftest maintain-refuses-a-reason-outside-the-set
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a maintenance reason"
                        (store/maintain! :whim nil nil :seat "x" {} nil))))
