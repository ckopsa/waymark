(ns factory10.week-labels-test
  "The org's own words for its first week (ticket 4eda448b): a fixture
  org's kinds yield the JSON week_page reads.

  Run: cd factory10 && clojure -M:test --focus factory10.week-labels-test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.week-labels :as week-labels]
            [jsonista.core :as j]))

(def ^:private prospect
  {:kind :prospect
   :schema [:map
            [:company :string]
            [:name {:optional true} :string]
            [:estimated_credit_usd :int]]
   :actions {:qualify {:display {:label "Qualify"}
                       :input [:map [:estimated_credit_usd {:x-display {:label "Estimated credit"}} :int]]}
             :records_in {:display {:label "Records complete"}}}})

(def ^:private invoice
  {:kind :invoice
   :display {:title :title}
   :schema [:map
            [:client :string]
            [:title :string]
            [:amount_usd {:x-display {:label "Amount"}} :int]
            [:fee_cents [:maybe :int]]
            [:total_label :string]
            [:due_on {:x-display {:label "Due"}} :string]]
   :actions {:send {:display {:label "Send to client"}}}})

(deftest an-org's-kinds-yield-their-labels
  (let [out (j/read-value (j/write-value-as-string (week-labels/labels [prospect invoice])))]
    (testing "the JSON shape: kind → title, fields, doors, money"
      (is (= #{"prospect" "invoice"} (set (keys out))))
      (is (= #{"title" "fields" "doors" "money"} (set (keys (get out "invoice"))))))
    (testing "the title: the display title, else the first of client, company, name, title"
      (is (= "title" (get-in out ["invoice" "title"])))
      (is (= "company" (get-in out ["prospect" "title"]))))
    (testing "the form's labels, a door's input included"
      (is (= {"estimated_credit_usd" "Estimated credit"} (get-in out ["prospect" "fields"])))
      (is (= "Amount" (get-in out ["invoice" "fields" "amount_usd"])))
      (is (= {"qualify" "Qualify" "records_in" "Records complete"} (get-in out ["prospect" "doors"]))))
    (testing "money is a number field named amount, total or fee: estimated_credit_usd is not"
      (is (= [] (get-in out ["prospect" "money"])))
      (is (= ["amount_usd" "fee_cents"] (get-in out ["invoice" "money"]))))))
