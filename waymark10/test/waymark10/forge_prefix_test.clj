(ns waymark10.forge-prefix-test
  "Which change ids are the forge's (R-12.32): a change minted for a
  walk row says `<kind>:<id>`, and one a forge source adopted says
  `<forge>:<repo>#<n>`. The sit reads the walk row out of the first and
  must read nothing out of the second, whichever forge it is."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.mcp]))

(def ^:private born-row-id #'waymark10.server.mcp/born-row-id)
(def ^:private unpushed-change? #'waymark10.server.mcp/unpushed-change?)

(defn- change [state data] {:state state :data data})

(deftest a-minted-change-names-its-walk-row
  (is (= "4f1c" (born-row-id (change :open {:born_from "ticket:4f1c"}))))
  (is (= "4f1c" (born-row-id (change :open {:change_id "ticket:4f1c"})))))

(deftest a-forge-owned-id-names-no-walk-row
  (testing "GitHub, as factory10 writes it"
    (is (nil? (born-row-id (change :submitted {:change_id "github:ckopsa/waymark#41"})))))
  (testing "Bitbucket, as an app over Bitbucket Cloud writes it"
    (is (nil? (born-row-id (change :submitted {:change_id "bitbucket:nav#56"}))))
    (is (nil? (born-row-id (change :submitted {:born_from "bitbucket:nav#56"})))
        "not a walk row named nav#56 of a kind named bitbucket")))

(deftest only-a-house-minted-change-is-unpushed
  (is (true? (unpushed-change? (change :open {:change_id "ticket:4f1c"}))))
  (is (false? (unpushed-change? (change :open {:change_id "github:ckopsa/waymark#41"}))))
  (is (false? (unpushed-change? (change :open {:change_id "bitbucket:nav#56"})))
      "an adopted Bitbucket pull request is never branched again"))
