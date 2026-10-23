(ns waymark10.ui-key-values-test
  "A row's page shows its filled values under its title and state chip,
  on the first screen (ticket 7c965f4d): money as dollars, a day as
  `Oct 29`, a list of records one line each. The source half runs
  everywhere; the behavioural half runs the helpers under node and SKIPS
  when `node` is not on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- source [f] (slurp (io/resource (str "waymark10/ui/" f))))

(defn- slice [src from to]
  (let [i (str/index-of src from)
        j (some->> i (str/index-of src to))]
    (assert (and i j) (str from " moved"))
    (subs src i j)))

(deftest the-values-sit-under-the-chip-and-above-the-doors
  (let [src (source "160-resource-surface.js")
        keys-at (str/index-of src "keyValues(doc, hints)")]
    (is keys-at)
    (is (< (str/index-of src "humanize(doc.state)") keys-at
           (str/index-of src "panel.append(bar)")))))

(defn- node? []
  (try (zero? (:exit (sh "node" "--version")))
       (catch Exception _ false)))

(def ^:private cases
  (str "const label = k => ({amount_usd: 'Amount', missing_records: 'Chases'})[k] || humanize(k);\n"
       "const said = (data, year) => console.log(JSON.stringify(\n"
       "  keyRows(data, year, label, () => false).map(r => [r[1], r[2]])));\n"
       "said({id: 'i1', amount_usd: 9600, due_on: '2026-11-29', note: '', _seen: 1,\n"
       "      self: '/api/invoice/i1'}, 2026);\n"
       "said({missing_records: [{record: 'payroll', last_chased_on: '2026-10-28'}],\n"
       "      credit_usd: 48000, records_due_on: '2027-01-15', client_id: 'c9',\n"
       "      member_ids: ['m1']}, 2026);\n"
       "said({total_cents: 125050, fee: 12.5, flag: true, a: 'x', b: 'x', c: 'x',\n"
       "      d: 'x', e: 'x', f: 'x'}, 2026);\n"))

(deftest a-row-says-its-key-values
  (if-not (node?)
    (println "SKIP a-row-says-its-key-values: no node on PATH")
    (let [core (source "100-core.js")
          js (str (slice core "const humanize = s =>" "\n};\n") "\n};\n"
                  (slice (source "150-values-parts.js") "const KEY_DAY" "function keyValues(")
                  cases)
          {:keys [exit out err]} (sh "node" "-e" js)
          [invoice study capped] (str/split-lines (str/trim out))]
      (is (zero? exit) err)
      (testing "an invoice: its amount in dollars and its due day, no id, path, _key or empty"
        (is (= "[[\"Amount\",\"$9,600\"],[\"Due\",\"Nov 29\"]]" invoice)))
      (testing "a study: its chases one line each, its credit, a day of another year"
        (is (= (str "[[\"Chases\",\"Payroll · chased Oct 28\"],[\"Credit usd\",\"$48,000\"],"
                    "[\"Records due\",\"Jan 15, 2027\"]]")
               study)))
      (testing "cents over 100, cents shown when not whole, at most 8, the rest valueCell's"
        (is (= (str "[[\"Total cents\",\"$1,250.50\"],[\"Fee\",\"$12.50\"],[\"Flag\",null],"
                    "[\"A\",null],[\"B\",null],[\"C\",null],[\"D\",null],[\"E\",null]]")
               capped))))))
