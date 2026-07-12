(ns waymark10.check-test
  "The declaration-time gate: only gate-passed values count, the
  report tallies kinds and warnings, and an assembly refusal
  propagates as the definition error the exit code reads."
  (:require [clojure.test :refer [deftest is]]
            [waymark10.check :as check]
            [waymark10.resource :as r]))

(r/defresource probe
  {:kind :check_probe
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 50}]]]
   :flow [[:open :close :closed
           {:one-way "Closing records completion; nothing external changes."
            :display {:label "Close"}}]]})

(deftest only-gate-passed-values-are-declarations
  (is (check/declaration? probe))
  (is (not (check/declaration? {:kind :raw :states [:a] :actions {}}))))

(deftest the-report-tallies-kinds-and-warnings
  (let [out (with-out-str
              (let [{:keys [kinds warnings]} (check/report [probe])]
                (is (= 1 kinds))
                (is (zero? warnings))))]
    (is (re-find #"check_probe ✓" out))))

(deftest an-assembly-refusal-surfaces-as-the-definition-error
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"one law per kind"
       (with-out-str (check/report [probe probe])))))
