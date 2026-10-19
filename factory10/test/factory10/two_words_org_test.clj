(ns factory10.two-words-org-test
  "Ticket 2f33bbc1: an org whose name holds an underscore loads one
  namespace everywhere, its name verbatim, `two_words.org`. The engine's
  org load boots it, conform answers no load finding, and law-text
  renders its page. The fixture is fixtures/orgs/two_words, a deps
  root of the :test alias. No database."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [factory10.law-text :refer [law-text]]
            [factorykit.conform :as conform]
            [waymark10.server.org :as org-boot]))

(def ^:private dir "fixtures/orgs/two_words")

(deftest the-engine's-org-load-boots-two_words.org
  (let [rs (org-boot/resources "two_words")]
    (is (= [:thing] (mapv :kind rs)))
    (is (every? #(= :two_words (:domain %)) rs))))

(deftest conform-answers-no-load-finding
  (let [fs (conform/conform dir)]
    (is (= [] (filterv #(= :load (:class %)) fs)) (pr-str fs))))

(deftest law-text-renders-two_words's-page
  (let [page (law-text dir)]
    (is (str/includes? page "Two Words"))
    (is (str/includes? page "thing"))))
