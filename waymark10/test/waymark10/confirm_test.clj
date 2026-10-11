(ns waymark10.confirm-test
  "The confirm sentence has one reading (waymark10.confirm), and the
  client and the MCP gate both read through it."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.client]
            [waymark10.confirm :as confirm]
            [waymark10.server.mcp]))

(deftest consequence-of-reads-the-description-then-the-label
  (testing "the declaration's :consequence rides display.description"
    (is (= "The link goes dead immediately and for good."
           (confirm/consequence-of
            {:display {:description "The link goes dead immediately and for good."
                       :label "Revoke"}}))))
  (testing "an entry with no description says its label"
    (is (= "Revoke" (confirm/consequence-of {:display {:label "Revoke"}}))))
  (testing "an entry with neither says the fallback"
    (is (= confirm/fallback (confirm/consequence-of {})))
    (is (= "This action requires confirmation." confirm/fallback)))
  (testing "the client and the MCP gate hold this accessor, not a copy"
    (is (identical? confirm/consequence-of
                    @#'waymark10.client/consequence-of))
    (is (identical? confirm/consequence-of
                    @#'waymark10.server.mcp/consequence-of))))
