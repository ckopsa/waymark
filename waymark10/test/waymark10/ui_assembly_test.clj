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
    (is (= 1 (count (re-seq #"<script id=\"theme-boot\">" page)))
        "…plus the head's theme boot, the one script outside that block")
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

(deftest the-feed-renderer-rides-the-page
  ;; the :feed collection view (waymark-h50): the fragment registers on
  ;; the dispatch seam and its snap-scroll CSS survives assembly
  (let [page (sut/assemble)]
    (is (str/includes? page "VIEW_RENDERERS.feed"))
    (is (str/includes? page "scroll-snap-type: y mandatory"))))

(deftest the-prefill-refetches-a-summary-doc
  ;; a collection row is an envelope-summary and render.clj drops
  ;; "data" from those, so an :edit {:prefill} action opened from the
  ;; grid (or a feed card) read an absent projection and built a blank
  ;; form. The dialog fetches the row's :self first when the doc it
  ;; holds carries no data.
  (let [page (sut/assemble)]
    (is (str/includes? page "!doc.data && doc.self"))
    (is (str/includes? page "prefillFromDoc(source, {properties:"))
    (is (str/includes? page "prefillFromDoc(source, input)")
        "the draft branch reads the refetched source too")))

(deftest the-marks-panel-rides-the-card
  ;; waymark-wxk: a verb whose declaration says `display.marks` opens a
  ;; per-piece selection IN PLACE on the card before it collects its
  ;; note. Three things are asserted and all three are the reason it is
  ;; a generic affordance rather than one kind's feature: the dispatch
  ;; reads the ADVERTISEMENT (never an action name), the part's own
  ;; decline door is found by the `display.reasons` it already carries
  ;; (never named), and the words come off the document's own `reasons`
  ;; — so no application kind name reaches this page. The markup hooks
  ;; are asserted too, because the panel is the one control on the card
  ;; that a person taps five of.
  (let [page (sut/assemble)]
    (is (str/includes? page "function marksPanel"))
    (is (str/includes? page "(entry.display || {}).marks")
        "the dispatch is the declaration's advertisement")
    (is (str/includes? page "((entry.display || {}).reasons) && !entry.input")
        "a part's decline door is found by what it advertises, not by its name")
    (is (str/includes? page "\"data-marks\": \"\""))
    (is (str/includes? page "\"data-mark\": \"\""))
    (is (str/includes? page "\"data-mark-choice\": \"keep\""))
    (is (str/includes? page "\"aria-pressed\": \"true\"")
        "Keep is the state a piece is already in, and the row says which")
    (is (str/includes? page "async function fireMark")
        "a mark is the decline and then the word behind it")
    (is (str/includes? page ".feed-marks {") "the panel's own CSS survives assembly")
    (is (str/includes? page "html[data-ui=\"mobile\"] .feed-mark-chips button.chip.mark")
        "…and a mark is fingertip-sized on a phone, like every other chip")
    (doseq [word ["outcome_piece" "not_this" "iterate"]]
      (is (not (str/includes? page word))
          (str "the generic page never learns an application's kind or"
               " door — found " word)))))

(deftest the-dashboard-renderer-rides-the-page
  ;; the dashboard screen (waymark-ggw): render() forks by kind to
  ;; renderDashboard (function declarations hoist across the one flat
  ;; <script>, so fragment order does not gate the call), and the slot
  ;; grid CSS survives assembly in both stylesheets
  (let [page (sut/assemble)]
    (is (str/includes? page "async function renderDashboard"))
    (is (str/includes? page "renderDashboard(view, body)")
        "the dispatch seam calls it")
    (is (str/includes? page ".slot-grid"))
    (is (str/includes? page "html[data-ui=\"mobile\"] .slot-grid"))))

;; ── the theme (waymark-88k) ───────────────────────────────────────
;; One palette, restated: the light tokens on :root, the dark ones
;; under prefers-color-scheme AND under an explicit data-theme, and a
;; head script that stamps the stored choice before the first paint.

(defn- stylesheet
  "The page's one <style> block — the three CSS fragments as served."
  [page]
  (second (re-find #"(?s)<style>(.*?)</style>" page)))

(defn- block
  "The declarations of the rule opening at `selector`, as a set of
  \"prop: value\" strings — no nesting inside a token block, so the
  first } after the selector closes it, and indentation is flattened
  so a rule nested in a media query compares to a top-level one."
  [css selector]
  (let [start (+ (str/index-of css selector) (count selector))
        body  (subs css start (+ start (str/index-of (subs css start) "}")))]
    (->> (str/split body #";")
         (map #(str/replace (str/trim %) #"\s+" " "))
         (remove str/blank?)
         set)))

(deftest the-page-dresses-for-light-and-dark
  ;; the whole switch, end to end: the meta that lets native chrome
  ;; follow, the before-first-paint stamp, both dark blocks, and the
  ;; three-seat control in the shell
  (let [page (sut/assemble)]
    (is (str/includes? page "<meta name=\"color-scheme\" content=\"light dark\">")
        "native controls and scrollbars follow the page")
    (is (< (str/index-of page "<script id=\"theme-boot\">")
           (str/index-of page "<style>"))
        "the stamp lands before the stylesheet — no flash of the other theme")
    (is (str/includes? page "localStorage.getItem(\"waymark.theme\")")
        "one storage key, shared with ui_lite.html")
    (is (str/includes? page "@media (prefers-color-scheme: dark)")
        "the system's preference applies with nothing stored")
    (is (str/includes? page ":root:not([data-theme=\"light\"])")
        "an explicit light beats a dark system")
    (is (str/includes? page ":root[data-theme=\"dark\"]")
        "an explicit dark beats a light system")
    (is (str/includes? page "id=\"themepick\"") "the shell carries the control")
    (doseq [seat ["system" "light" "dark"]]
      (is (str/includes? page (str "data-theme-choice=\"" seat "\"")) seat))
    (is (str/includes? page "localStorage.removeItem(\"waymark.theme\")")
        "\"system\" is the absence of a stored word, not a third colour")))

(deftest the-two-dark-blocks-say-the-same-thing
  ;; CSS cannot share one body across a media boundary, so the dark
  ;; palette is written twice — once for the system's preference, once
  ;; for the explicit choice. A drifted copy is a theme that changes
  ;; when you toggle it away and back, so the two are compared here
  ;; rather than trusted to a reviewer's eye.
  (let [css (stylesheet (sut/assemble))]
    (is (= (block css ":root:not([data-theme=\"light\"]) {")
           (block css ":root[data-theme=\"dark\"] {")))))

(deftest no-colour-escapes-the-palette
  ;; every colour the page paints is named in the token blocks and
  ;; read back through var(): a literal anywhere else is a spot that
  ;; would keep its light value under a dark theme. The three blocks
  ;; are contiguous (light :root, the media block, the explicit one),
  ;; so cutting from the first to the close of the last leaves exactly
  ;; the stylesheet that must be token-only.
  (let [css     (stylesheet (sut/assemble))
        dark    (str/index-of css ":root[data-theme=\"dark\"] {")
        close   (+ dark (str/index-of (subs css dark) "}") 1)
        outside (str (subs css 0 (str/index-of css ":root {"))
                     (subs css close))]
    ;; a hex colour is a VALUE, so it ends the declaration or an
    ;; argument list — which is what keeps #feed and friends out
    (is (empty? (re-seq #"#[0-9a-fA-F]{3,8}[;,)]" outside))
        "no literal hex outside the palette")
    (is (empty? (re-seq #"\b(rgba?|hsla?)\(" outside))
        "no literal rgb()/hsl() outside the palette")))
