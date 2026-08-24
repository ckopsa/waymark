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
            [waymark10.modules :as modules]
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

;; The probe above with its prose taken away — the stand-in for a kind
;; nobody declared that the battery reaches anyway. It exists because
;; waymark-0y7 cleared the LAST unkind enrolled kind, so there is no
;; framework form left to point at; see the reach test below.
(r/defresource wordless-probe
  {:kind :check_wordless_probe
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :label-template "{data.name}"
   :schema [:map [:name [:string {:min 1 :max 50}]]]
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
  ;; silent; some of the framework's own kinds are not, and that is
  ;; the point of widening the report rather than an accident of it
  ;; (docs/spec-usability-battery.md).
  ;;
  ;; The witness used to be `grant`, until waymark-7rw cleared the
  ;; access kinds and a cleared enrolled kind stops being listed at
  ;; all; then `definition`, until waymark-0y7 cleared the last four.
  ;; THE FIX-LIST IS EMPTY NOW, and that is what this asserts: a
  ;; compliant probe assembled against the whole enrollment table
  ;; reports zero. The day a framework kind lands unkind again this
  ;; goes red, which is the burndown's standing guard — the tally is
  ;; the regression test the fix-list turned into.
  (let [out (with-out-str
              (let [{:keys [kinds warnings]} (check/report [probe])]
                (is (= 1 kinds))
                (is (zero? warnings))))]
    (is (re-find #"check_probe ✓" out))
    (is (not (re-find #"\(enrolled\)" out))
        "every enrolled kind is silent, so none of them takes a row")))

(deftest the-usability-battery-reaches-the-kinds-nobody-declared
  ;; The widening this asserts — a kind NOBODY DECLARED is batteried
  ;; and printed beside the app's own — used to be witnessed by
  ;; whichever framework form happened to be unkind that month. That
  ;; made a passing test out of an unfixed fix-list, and it re-filed
  ;; itself every time the list drained (grant → definition → nothing).
  ;; So the enrollment table stands in for one report instead: the
  ;; wordless probe is enrolled by fiat, and the reach is read off the
  ;; row it takes. What is under test is the machinery, which is what
  ;; was always meant.
  (with-redefs [modules/enrolled (fn [_ _] [wordless-probe])]
    (let [out (with-out-str (check/report [probe]))]
      (is (re-find #"check_wordless_probe \(enrolled\) — " out)
          "a kind nobody declared takes a row of its own")
      (is (re-find #"\[display-prose\] the create door renders without prose"
                   out)
          "and the battery judged it, not merely listed it"))))

(deftest an-assembly-refusal-surfaces-as-the-definition-error
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"one law per kind"
       (with-out-str (check/report [probe probe])))))
