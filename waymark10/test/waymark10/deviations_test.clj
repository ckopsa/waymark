(ns waymark10.deviations-test
  "Recorded deviations are fingerprint-carried, advertisement-class
  law: editing one diffs and mints a revision; carrying none leaves
  the hash byte-identical to the pre-deviations era."
  (:require [clojure.test :refer [deftest is]]
            [waymark10.dev :as dev]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]))

(def ^:private base
  {:kind :dev_probe
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 50}]]]
   :flow [[:open :close :closed
           {:one-way "Closing records completion; nothing external changes."
            :display {:label "Close"}}]]})

(defn- hash-of [rmap]
  (fp/fingerprint-hash (fp/fingerprint-of (r/normalize-resource rmap))))

(deftest empty-deviations-equal-absent
  (is (= (hash-of base) (hash-of (assoc base :deviations [])))))

(deftest a-deviation-is-advertisement-class-law
  (let [with-d (assoc base :deviations ["The close door has no undo — closing is honest history."])
        old (fp/fingerprint-of (r/normalize-resource base))
        new (fp/fingerprint-of (r/normalize-resource with-d))
        diff (fp/diff-fingerprints old new)
        entries (concat (:added diff) (:removed diff) (:changed diff))]
    (is (not= (fp/fingerprint-hash old) (fp/fingerprint-hash new)))
    (is (some #(and (re-find #"^deviations" (:path %))
                    (= :advertisement (:class %)))
              entries)
        (pr-str entries))
    (is (empty? (fp/stale-facts diff)))))

(deftest a-deviation-must-be-a-sentence
  (doseq [bad [["  "] "not a vector" [:kw]]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"deviations"
         (r/normalize-resource (assoc base :deviations bad))))))

(deftest explain-renders-the-deviations
  (let [rdef (r/resource (assoc base :deviations ["One recorded sentence."]))
        out (with-out-str (dev/explain rdef))]
    (is (re-find #"deviation: One recorded sentence\." out))))
