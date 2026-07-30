(ns waymark10.ui-assembly-test
  "The assembled UI page: every fragment resolves off the classpath and
  concatenation yields ONE self-contained page — one <style>, one
  <script>, the mobile-stamp anchor intact. Serving behavior (mobile
  stamp, ?ui= overrides, no external hosts) stays covered by
  waymark10.ui-test through the real handler."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [waymark10.server.ui-assembly :as sut]))

(deftest every-fragment-is-on-the-classpath
  (doseq [f sut/fragments]
    (is (some? (io/resource f)) f)))

(deftest assembles-one-self-contained-page
  (let [page (sut/assemble)]
    (is (str/starts-with? page "<!DOCTYPE html>"))
    (is (str/ends-with? page "</html>\n"))
    (is (= 1 (count (re-seq #"<style>" page))) "exactly one <style> block")
    (is (= 1 (count (re-seq #"<script>" page))) "exactly one <script> block")
    (is (str/includes? page "<html lang=\"en\">") "the mobile-stamp anchor")
    (is (str/includes? page "\"use strict\";"))
    (is (str/includes? page "render();"))
    (is (str/includes? page "html[data-ui=\"mobile\"]"))))

(deftest the-deck-renderer-registers-against-the-dispatch-seam
  ;; order matters in one flat <script>: the registry (110) must be
  ;; initialized before 133 assigns into it — const, not hoisted
  (let [page (sut/assemble)]
    (is (str/includes? page "VIEW_RENDERERS.deck"))
    (is (< (str/index-of page "const VIEW_RENDERERS = {};")
           (str/index-of page "VIEW_RENDERERS.deck"))
        "the registry is declared before the deck registers")))
