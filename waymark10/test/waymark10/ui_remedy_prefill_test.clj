(ns waymark10.ui-remedy-prefill-test
  "A bound remedy's chip opens its door's form filled with what the
  refusal resolved (resolved_remedies): the same-page action through
  actionDialog, a create on another kind by reading its collection
  first. The page is not executed here, so this pins the seams."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.ui-assembly :as sut]))

(deftest a-bound-remedy-chip-opens-its-form-prefilled
  (let [page (sut/assemble)]
    (testing "every caller hands the chips the refusal's resolved remedies"
      (is (str/includes? page "remedyChips(entry.remedies, doc, null, entry.resolved_remedies)"))
      (is (str/includes? page "remedyChips(g[0][1].remedies, doc, null, g[0][1].resolved_remedies)"))
      (is (= 2 (count (re-seq #"remedyChips\(w\.remedies, doc, \(\) => closeDlg\(\), w\.resolved_remedies\)"
                              page)))
          "beside a rehearsal's warning and a warned submit")
      (is (= 2 (count (re-seq #"questOffer\(w\.remedies\)" page)))
          "the quest offer stays beside them"))
    (testing "a chip reads the input bound to its own door, on this row"
      (is (str/includes? page "function boundInput(resolved, token, doc) {"))
      (is (str/includes? page "const prefill = boundInput(resolved, token, doc);")))
    (testing "a same-page action opens its dialog filled"
      (is (str/includes? page "actionDialog({name: action, entry: doc.actions[action], doc,")))
    (testing "a create elsewhere reads its collection, then opens its create filled"
      (is (str/includes? page "remedyCreate(col, prefill)"))
      (is (str/includes? page "async function remedyCreate(col, prefill) {")))
    (testing "a chip with nothing bound still points the way it did"
      (is (str/includes? page "else pulseAction(action);"))
      (is (str/includes? page "opens the ${pretty(kind)}s page")))))
