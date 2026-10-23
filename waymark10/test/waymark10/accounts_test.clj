(ns waymark10.accounts-test
  "Ledger step 3a (docs/spec-ledger-money.md §3, §10): a kind declares
  :accounts and a house :house-accounts, each with its unit. A whole one
  loads, fingerprints and audits; a malformed one is refused at load
  with its sentence; and no existing law's fingerprint moves."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fingerprint :as fp]
            [waymark10.money :as money]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(defn- product [& {:as overrides}]
  (merge {:kind :ledger_product
          :states [:open]
          :initial :open
          :terminal #{}
          :allow-dead #{:open}
          :summary "{data.name} · {state}"
          :schema [:map [:name [:string {:max 40}]]]
          :accounts {:on_hand {:unit :count :never-below 0}
                     :reserved {:unit :count :never-below 0 :never-above :on_hand}
                     :paid {:unit [:money "USD"]}
                     :clay {:unit :decimal :never-below 0.5M}}}
         overrides))

(defn- refusal [rmap]
  (try (r/resource rmap) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(defn- hash-of [rmap]
  (fp/fingerprint-hash (r/fingerprint (r/resource rmap))))

(deftest a-law-with-accounts-loads
  (let [rd (r/resource (product))]
    (is (= {:unit [:money "USD"]} (get-in rd [:accounts :paid])))
    (is (= :on_hand (get-in rd [:accounts :reserved :never-above])))))

(deftest it-fingerprints
  (is (= {"clay" {"never_below" {"dec" "0.5"} "unit" "decimal"}
          "on_hand" {"never_below" 0 "unit" "count"}
          "paid" {"unit" ["money" "USD"]}
          "reserved" {"never_above" "on_hand" "never_below" 0 "unit" "count"}}
         (get (r/fingerprint (r/resource (product))) "accounts")))
  (testing "a unit and a bound are law"
    (is (not= (hash-of (product))
              (hash-of (assoc-in (product) [:accounts :paid :unit] [:money "EUR"]))))
    (is (not= (hash-of (product))
              (hash-of (assoc-in (product) [:accounts :on_hand :never-below] 1)))))
  (testing "a kind with none carries no facet"
    (is (not (contains? (r/fingerprint (r/resource (dissoc (product) :accounts)))
                        "accounts")))))

(deftest the-audit-sees-them
  (let [info #(:waymark10/info (meta (r/resource %)))]
    (is (some #(str/starts-with? % "[account-unbounded] info: account on_hand")
              (info (product :accounts {:on_hand {:unit :count}
                                        :fees {:unit [:money "USD"]}}))))
    (is (not-any? #(str/includes? % "account fees")
                  (info (product :accounts {:fees {:unit [:money "USD"]}})))
        "money is not a count")
    (is (not-any? #(str/includes? % "[account-unbounded]")
                  (info (product :accounts {:owed {:unit :count :may-go-negative true}})))
        "saying it may go negative answers the question")
    (is (not-any? #(str/includes? % "[account-unbounded]") (info (product)))
        "a bounded count is quiet")))

(deftest a-malformed-one-is-refused-with-its-sentence
  (doseq [[accts says] [[{:on_hand {:unit :each}} ":unit is :count, :decimal or [:money \"USD\"]"]
                        [{:paid {:unit [:money "usd"]}} ":unit is :count"]
                        [{:on_hand {:unit :count :never-below 0.5M}} "whole number"]
                        [{:clay {:unit :decimal :never-below 0.5}} "never a float"]
                        [{:refunded {:unit [:money "USD"] :never-above :paid}}
                         "names no account declared beside it"]
                        [{:on_hand {:unit :count} :paid {:unit [:money "USD"] :never-above :on_hand}}
                         "is of another unit"]
                        [{:on_hand {:unit :count :floor 0}} "names :floor"]
                        [{:on_hand {:unit :count :never-below 0 :may-go-negative true}}
                         "one or the other"]
                        [{"on_hand" {:unit :count}} "is not an account name"]
                        [[:on_hand] "maps each account's name"]
                        [{:name {:unit :count}} "is also a field"]]]
    (let [msg (str (refusal (product :accounts accts)))]
      (is (str/includes? msg "ledger_product: :accounts") (pr-str accts))
      (is (str/includes? msg says) (pr-str accts)))))

(deftest a-house-declares-its-accounts
  (is (empty? (money/account-problems ":house-accounts"
                                      {:processor_in {:unit [:money "USD"]}
                                       :fees {:unit [:money "USD"]}})))
  (is (empty? (money/account-problems ":house-accounts" {})) "a house may declare none")
  (is (= [":house-accounts fees :unit is :count, :decimal or [:money \"USD\"]; got nil"]
         (money/account-problems ":house-accounts" {:fees {}}))))

(defn- kiln [kind]
  (r/resource {:kind kind
               :plural (str (name kind) "s")
               :states [:open]
               :initial :open
               :terminal #{}
               :allow-dead #{:open}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]]]
               :actions {:fire {:from #{:open} :to :open
                                :input [:map [:pieces [:int {:min 1}]]]
                                :posts '[{:account [:house :made] :amount (input :pieces) :sign :+}]
                                :safety {:idempotent false :reversible false :confirm false}
                                :display {:label "Fire"}}}}))

(defn- boot-refusal [resources]
  (try (engine/engine {:storage (memory/storage) :resources resources}) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest an-org-declares-its-house-accounts-in-its-own-module
  (let [elena (t/principal {:id "elena" :display "Elena"})
        eng (engine/engine {:storage (memory/storage)
                            :resources (r/in-domain :kiln_a {:house-accounts {:made {:unit :count}}}
                                                    [(kiln :ha_kiln)])})
        id (str (get-in (inv/create! eng :ha_kiln {:name "east"} {:principal elena}) [:row :id]))
        over (:balance-over (inv/render-hooks eng))]
    (testing "the org's house account is the engine's, and declaring it enrols the ledger"
      (is (= {:made {:unit :count}} (:house-accounts eng))))
    (inv/invoke! eng :ha_kiln id :fire {:pieces 12} {:principal elena :idempotency-key "ha-1"})
    (testing "a door posts to [:house :made] and the balance reads"
      (is (= 12 (over :house nil :made nil nil)))))
  (testing "in-domain judges the map it is given"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"in-domain: :house-accounts made :unit"
                          (r/in-domain :kiln_a {:house-accounts {:made {}}} [(kiln :ha_kiln)]))))
  (testing "a house address no house account declares is refused at load, naming it and its door"
    (let [msg (boot-refusal [(kiln :ha_kiln)])]
      (is (str/includes? (str msg) "[house-undeclared]") msg)
      (is (str/includes? (str msg) "fire posts to house made") msg)))
  (testing "two orgs giving one house account two units refuse the boot, naming both"
    (let [msg (boot-refusal (concat (r/in-domain :kiln_a {:house-accounts {:made {:unit :count}}}
                                                 [(kiln :ha_kiln)])
                                    (r/in-domain :kiln_b {:house-accounts {:made {:unit :decimal}}}
                                                 [(kiln :hb_kiln)])))]
      (is (str/includes? (str msg) "domain kiln_a (ha_kiln)") msg)
      (is (str/includes? (str msg) "domain kiln_b (hb_kiln)") msg))))

(deftest no-existing-laws-fingerprint-moves
  ;; the hash money-test and decision-sugar-test pin for approval_request:
  ;; a kind that declares no accounts hashes exactly as before
  (is (= "70e5fdacf5233d1337b5c66acfe4d7e4718e33b73a0e3d9265f2b671cf56c1f0"
         (fp/fingerprint-hash (r/fingerprint grants/approval-request)))))
