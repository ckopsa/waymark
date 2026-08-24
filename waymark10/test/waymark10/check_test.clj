(ns waymark10.check-test
  "The declaration-time gate: only gate-passed values count, the
  report tallies kinds and warnings, and an assembly refusal
  propagates as the definition error the exit code reads.

  The probe below is a COMPLIANT declaration on purpose — the report's
  tally now folds the usability battery (waymark-0ee) in beside the
  import gate's own warnings, so a probe that wanted a label or a
  hint sentence would make this namespace's zero-warning assertion a
  test of the battery instead of a test of the tally."
  (:require [clojure.test :refer [deftest is]]
            [waymark10.check :as check]
            [waymark10.resource :as r]))

(r/defresource probe
  {:kind :check_probe
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :schema [:map [:name {:x-display {:label "Name"
                                     :help "What to call this one."}}
                  [:string {:min 1 :max 50}]]]
   :flow [[:open :close :closed
           {:one-way "Closing records completion; nothing external changes."
            :display {:label "Close"}}]]})

(deftest only-gate-passed-values-are-declarations
  (is (check/declaration? probe))
  (is (not (check/declaration? {:kind :raw :states [:a] :actions {}}))))

(deftest the-report-tallies-kinds-and-warnings
  ;; :kinds counts the APPLICATION's declarations — one here — while
  ;; :warnings is the whole report's tally, and since waymark-0ee the
  ;; report reaches the enrolled kinds too. The compliant probe is
  ;; silent; the grant, the member and their siblings are not, and
  ;; that is the point of widening the report rather than an accident
  ;; of it (docs/spec-usability-battery.md).
  (let [out (with-out-str
              (let [{:keys [kinds warnings]} (check/report [probe])]
                (is (= 1 kinds))
                (is (pos? warnings))))]
    (is (re-find #"check_probe ✓" out))
    (is (re-find #"grant \(enrolled\)" out))))

(deftest the-usability-battery-reaches-the-kinds-nobody-declared
  (let [out (with-out-str (check/report [probe]))]
    (is (re-find #"\[effort-honesty\] .* :scope is free text" out)
        "the framework's own grant form is visible from the app's gate")))

(deftest an-assembly-refusal-surfaces-as-the-definition-error
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"one law per kind"
       (with-out-str (check/report [probe probe])))))
