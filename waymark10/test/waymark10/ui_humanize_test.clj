(ns waymark10.ui-humanize-test
  "Forms, lists and state chips show a key in words when no :display
  label is declared (ticket 0fb72975). One helper, `humanize`
  (ui/100-core.js), reads a field name, an enum value and a state name
  alike, and the presence chip (ui/220-boot.js) reads a seat's name
  through it too, so they cannot drift. Same two halves as
  ui_presence_chip_test: the source invariant runs everywhere; the
  behavioural half runs humanize under node and SKIPS when `node` is
  not on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- source [f] (slurp (io/resource (str "waymark10/ui/" f))))

(deftest every-surface-reads-the-one-helper
  (testing "one helper, in core"
    (is (str/includes? (source "100-core.js") "const humanize = s =>")))
  (testing "a field's label in a list, a table and a row page"
    (is (str/includes? (source "110-discovery-routing.js") "return humanize(f);")))
  (testing "a create form's labels, top level and sub-form"
    (let [src (source "170-forms.js")]
      (is (str/includes? src "el(\"b\", {}, xd.label || humanize(name))"))
      (is (str/includes? src "el(\"b\", {}, xd.label || humanize(sub))"))
      (is (not (str/includes? src "xd.label || name)")))
      (is (not (str/includes? src "xd.label || sub)")))))
  (testing "an enum's option, and its cell in a list"
    (is (str/includes? (source "170-forms.js") "choices[String(v)] || humanize(v)"))
    (is (str/includes? (source "150-values-parts.js")
                       "(xd.choices || {})[value] || humanize(value)")))
  (testing "a state chip, in a list and on a row's page"
    (is (str/includes? (source "130-collection.js")
                       "{class:\"statechip\"}, humanize(item.state)"))
    (is (str/includes? (source "160-resource-surface.js")
                       "title: kind}, humanize(doc.state)")))
  (testing "the presence chip shares it"
    (is (str/includes? (source "220-boot.js") "humanize(m[1])"))))

(defn- humanize-js []
  (let [src (source "100-core.js")
        from (str/index-of src "const humanize = s =>")
        to (str/index-of src "\n};\n" from)]
    (assert (and from to) "humanize moved")
    (subs src from (+ to 4))))

;; the keys the 00:28 dress take caught on screen: the study create
;; form's labels, the prospects list's enum value, the state chips
(def ^:private leaked
  ["principal_told_on" "missing_records" "records_complete_on" "prospect_id"
   "tax_year" "credit_usd" "last_chased_on" "records_due_on" "filing_date"
   "cpa_referral" "drafting" "review" "open" "signed_off"])

(defn- node? []
  (try (zero? (:exit (sh "node" "--version")))
       (catch Exception _ false)))

(deftest a-key-reads-as-words
  (if-not (node?)
    (println "SKIP a-key-reads-as-words: no node on PATH")
    (let [js (str (humanize-js)
                  "console.log([" (str/join "," (map #(str "'" % "'") leaked))
                  "].map(humanize).join('\\n'));")
          {:keys [exit out err]} (sh "node" "-e" js)
          words (zipmap leaked (str/split-lines (str/trim out)))]
      (is (zero? exit) err)
      (testing "no underscore reaches a label, a value or a chip"
        (is (= (count leaked) (count words)))
        (is (every? #(not (str/includes? % "_")) (vals words)) (pr-str words)))
      (testing "the decided spellings"
        (is (= "Principal told" (words "principal_told_on")))
        (is (= "Prospect" (words "prospect_id")))
        (is (= "Tax year" (words "tax_year")))
        (is (= "Cpa referral" (words "cpa_referral")))
        (is (= "Signed off" (words "signed_off")))
        (is (= "Records complete" (words "records_complete_on")))
        (is (= "Open" (words "open")))))))
