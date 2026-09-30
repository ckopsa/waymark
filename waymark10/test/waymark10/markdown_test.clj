(ns waymark10.markdown-test
  "The prose fields' renderer: markdown in, safe HTML out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.markdown :as md]))

(deftest a-ticket-detail-renders-as-html
  (let [html (md/render
              (str "# Title\n\n"
                   "- one\n- [x] done\n\n"
                   "| a | b |\n|---|---|\n| 1 | 2 |\n\n"
                   "```clojure\n(+ 1 2)\n```\n\n"
                   "[site](https://example.com)"))]
    (testing "heading and list"
      (is (str/includes? html "<h1>Title</h1>"))
      (is (str/includes? html "<li>one</li>")))
    (testing "task list"
      (is (str/includes? html "type=\"checkbox\"")))
    (testing "table"
      (is (str/includes? html "<table>"))
      (is (str/includes? html "<td>1</td>")))
    (testing "fenced code"
      (is (str/includes? html "<code class=\"language-clojure\">(+ 1 2)")))
    (testing "link, with its rel"
      (is (str/includes? html "href=\"https://example.com\""))
      (is (str/includes? html "rel=\"noopener noreferrer\"")))))

(deftest nothing-a-writer-sends-can-run
  (testing "a script tag arrives escaped"
    (let [html (md/render "<script>alert(1)</script>")]
      (is (not (str/includes? html "<script")))
      (is (str/includes? html "&lt;script&gt;"))))
  (testing "an onerror attribute arrives as text, in no tag"
    (let [html (md/render "hi <img src=x onerror=alert(1)> there")]
      (is (not (str/includes? html "<img")))
      (is (str/includes? html "&lt;img"))))
  (testing "a javascript: link keeps no href"
    (let [html (md/render "[click](javascript:alert(1)) and <javascript:alert(2)>")]
      (is (not (re-find #"href=\"[^\"]*javascript" (str/lower-case html))))
      (is (str/includes? html "click"))))
  (testing "mailto survives"
    (is (str/includes? (md/render "[me](mailto:a@example.com)")
                       "href=\"mailto:a@example.com\"")))
  (testing "an image renders as a link"
    (let [html (md/render "![a cat](https://example.com/cat.png)")]
      (is (not (str/includes? html "<img")))
      (is (str/includes? html "href=\"https://example.com/cat.png\""))
      (is (str/includes? html ">a cat</a>")))))

(deftest plain-text-stays-plain
  (is (= "<p>Just some words<br />\nand more</p>\n<p>Second paragraph</p>\n"
         (md/render "Just some words\nand more\n\nSecond paragraph")))
  (is (= "" (md/render nil))))
