(ns waymark10.money-test
  "Ledger step 1 (docs/spec-ledger-money.md §2, §14): :waymark/money is
  a type with a wire form, storage, arithmetic and a fingerprint — and
  adding it moves no existing law's fingerprint."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.expr :as e]
            [waymark10.fingerprint :as fp]
            [waymark10.money :as money]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.grants :as grants]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire]))

(defn- ev
  "A law form, checked and normalized as a declaration's is, evaluated."
  ([form] (ev form {}))
  ([form scope]
   (is (empty? (e/problems form)) (pr-str form))
   (e/evaluate (e/normalize form) scope)))

(defn- usd [s] (money/money s "USD"))

(defn- same-money? [expected actual]
  (and (money/money? actual)
       (= (:currency expected) (:currency actual))
       (zero? (.compareTo ^java.math.BigDecimal (:amount expected)
                          ^java.math.BigDecimal (:amount actual)))))

;; ── the §2 table ─────────────────────────────────────────────────────

(deftest money-plus-minus-money
  (testing "same currency: money"
    (is (same-money? (usd "20.50") (ev '(+ (money "18.00" "USD") (money "2.50" "USD")))))
    (is (same-money? (usd "15.50") (ev '(- (money "18.00" "USD") (money "2.50" "USD")))))
    (is (same-money? (usd "-18") (ev '(- (money "18" "USD"))))))
  (testing "other currency: unknown, and it says which two"
    (doseq [op ['+ '-]]
      (let [v (ev (list op '(money "18.00" "USD") '(money "2.50" "EUR")))]
        (is (e/unknown? v) (str op))
        (is (str/includes? (str (e/unknown-why v)) "USD"))
        (is (str/includes? (str (e/unknown-why v)) "EUR"))))))

(deftest money-times-a-number
  (is (same-money? (usd "54.00") (ev '(* (money "18.00" "USD") 3))))
  (is (same-money? (usd "1.234567") (ev '(* 1.234567M (money "1" "USD")))))
  (is (= 6 (.scale ^java.math.BigDecimal (:amount (ev '(* (money "1.5" "USD") 0.00001M)))))
      "unrounded: every digit kept"))

(deftest money-divided-by-a-number
  (is (same-money? (usd "4.5") (ev '(/ (money "18" "USD") 4))))
  (let [third (ev '(/ (money "10" "USD") 3))]
    (is (same-money? (usd "3.333333333333") third)
        "a quotient that does not terminate stops at 12 places, no exception")
    (is (= 12 (.scale ^java.math.BigDecimal (:amount third)))))
  (is (nil? (ev '(/ (money "10" "USD") 0))) "÷ 0 is a missing value, not a crash"))

(deftest money-compared-with-money
  (testing "same currency: boolean"
    (is (true? (ev '(< (money "1.99" "USD") (money "2" "USD")))))
    (is (true? (ev '(<= (money "2" "USD") (money "2.00" "USD")))))
    (is (true? (ev '(= (money "18.00" "USD") (money "18" "USD")))))
    (is (false? (ev '(> (money "1" "USD") (money "2" "USD")))))
    (is (true? (ev '(>= (money "2" "USD") (money "1" "USD")))))
    (let [scope {:data {:a (usd "18.00") :b (usd "18")}}]
      (is (true? (ev '(= (data :a) (data :b)) scope)) "the amount compares, not its scale")))
  (testing "other currency: unknown"
    (doseq [op ['< '<= '= '>= '> 'not=]]
      (let [v (ev (list op '(money "1" "USD") '(money "1" "EUR")))]
        (is (e/unknown? v) (str op))
        (is (str/includes? (str (e/unknown-why v)) "EUR") (str op))))))

(deftest money-plus-a-bare-number
  (is (e/unknown? (ev '(+ (money "1" "USD") 1))) "a bare number is not money")
  (is (e/unknown? (ev '(+ 1.5M (money "1" "USD")))))
  (is (e/unknown? (ev '(- (money "1" "USD") 1)))))

;; ── the law record and its readers ───────────────────────────────────

(deftest the-law-record-and-its-readers
  (let [scope {:data {:price (usd "18.00")}}]
    (is (zero? (.compareTo 18M ^java.math.BigDecimal (ev '(amount (data :price)) scope))))
    (is (= "USD" (ev '(currency (data :price)) scope)))
    (is (nil? (ev '(amount (data :missing)) scope)))
    (is (same-money? (usd "18") (ev '(money "18.00" "USD")))))
  (is (seq (e/problems '(money 18 "USD"))) "the amount is written as a string")
  (is (seq (e/problems '(money "1e3" "USD"))) "…as plain digits")
  (is (seq (e/problems '(money "18.00" "usd"))) "the currency is an ISO code"))

;; ── the wire form ────────────────────────────────────────────────────

(deftest the-wire-form
  (let [f [:waymark/money]
        decoded (schema/decode f {:amount "18.00" :currency "USD"})]
    (is (same-money? (usd "18.00") decoded))
    (is (schema/validate f decoded))
    (is (= {:amount "18.00" :currency "USD"} (schema/encode f decoded))
        "the wire keeps the amount as it was written")
    (testing "a JSON number for the amount is refused, never read"
      (is (not (schema/validate f (schema/decode f {:amount 18.0M :currency "USD"}))))
      (is (not (schema/validate f (schema/decode f {:amount 18 :currency "USD"})))))
    (testing "a bare amount string takes the field's declared currency"
      (is (same-money? (usd "18.00")
                       (schema/decode [:waymark/money {:currency "USD"}] "18.00")))
      (is (not (schema/validate f (schema/decode f "18.00")))
          "with no currency declared, a bare string is no money"))
    (testing "a declared currency admits that one; bounds compare amounts"
      (is (not (schema/validate [:waymark/money {:currency "USD"}] (money/money "1" "EUR"))))
      (is (not (schema/validate [:waymark/money {:min "0"}] (usd "-0.01"))))
      (is (schema/validate [:waymark/money {:min "0"}] (usd "0.00"))))))

;; ── rounding (step 2) ───────────────────────────────────────────────

(deftest round-goes-to-the-currencys-minor-unit
  (is (same-money? (usd "3.33") (ev '(round (/ (money "10" "USD") 3)))))
  (is (= 2 (.scale ^java.math.BigDecimal (:amount (ev '(round (money "18" "USD"))))))
      "USD has two places")
  (is (same-money? (money/money "1234" "JPY") (ev '(round (money "1234.5" "JPY"))))
      "JPY has none")
  (is (same-money? (money/money "1.235" "KWD") (ev '(round (money "1.23456" "KWD"))))
      "KWD has three")
  (is (nil? (ev '(round (data :missing)))))
  (is (e/unknown? (ev '(round (money "1" "XXX")))) "no minor unit, no rounding")
  (is (seq (e/problems '(round (money "1" "USD") 2))) "round takes one expression"))

(deftest the-house-rounding-mode
  (testing "half-even unless the house says: a half goes to the even cent"
    (is (same-money? (usd "0.12") (ev '(round (money "0.125" "USD")))))
    (is (same-money? (usd "0.12") (ev '(round (money "0.125" "USD")) {:rounding :half-even}))))
  (testing "half-up when the house says so"
    (is (same-money? (usd "0.13") (ev '(round (money "0.125" "USD")) {:rounding :half-up}))))
  (testing "the house's :rounding is one of the two"
    (is (= :half-even (money/rounding-mode nil)))
    (is (= :half-up (money/rounding-mode :half-up)))
    (is (nil? (money/rounding-mode :ceiling)))))

(deftest an-unrounded-write-is-refused
  (let [f [:waymark/money]
        why (fn [m] (first (:price (schema/errors [:map [:price :waymark/money]] {:price m}))))]
    (is (schema/validate f (usd "18.00")))
    (is (schema/validate f (usd "18.000")) "trailing zeros are at the scale")
    (is (not (schema/validate f (usd "18.005"))))
    (is (not (schema/validate f (money/money "1.5" "JPY"))))
    (is (schema/validate f (money/money "1.5" "XXX")) "no minor unit, no scale to hold it to")
    (testing "the refusal names the rounded value"
      (is (str/includes? (why (usd "18.004")) "[money-unrounded]"))
      (is (str/includes? (why (usd "18.004")) "rounds to 18.00 USD"))
      (is (str/includes? (why (usd "0.125")) "0.12 USD half-even, 0.13 USD half-up")
          "a half names both modes"))
    (is (str/starts-with? (why 18) "must be money") "anything else keeps its sentence")))

;; ── storage ──────────────────────────────────────────────────────────

(deftest the-two-storage-columns
  (let [rmap {:kind :m_order
              :plural "m_orders"
              :schema [:map [:price :waymark/money] [:note :string]]
              :filterable {:price #{:eq}}}
        cols (into {} (map (juxt :name identity))
                   (:columns (store/kind-projection rmap)))]
    (is (= "numeric" (get-in cols ["f_price" :type])))
    (is (str/includes? (get-in cols ["f_price" :ddl])
                       "(data->'price'->>'amount')::numeric"))
    (is (str/starts-with? (get-in cols ["f_price_currency" :ddl])
                          "f_price_currency char(3) GENERATED ALWAYS AS"))
    (is (= "character" (get-in cols ["f_price_currency" :type]))
        "as information_schema spells char(3), so the migrate planner sees no drift")))

;; ── the fingerprint ──────────────────────────────────────────────────

(deftest the-fingerprint-form
  (is (= {"dec" "18" "cur" "USD"} (wire/form->wire (e/normalize '(money "18.00" "USD")))))
  (is (= (e/normalize '(money "18.00" "USD")) (wire/wire->form {"dec" "18" "cur" "USD"}))
      "the tree reads back into the form")
  (is (= (wire/form->wire (e/normalize '(< (data :copay) (money "20.0" "USD"))))
         (wire/form->wire (e/normalize '(< (data :copay) (money "20" "USD")))))
      "two spellings of one amount are one law")
  (is (= {"dec" "18.00" "cur" "USD"} (wire/dec-nodes (usd "18.00")))
      "a money value digests as the same node")
  (is (string? (wire/digest (wire/dec-nodes {:price (usd "18.00")})))))

(deftest no-existing-laws-fingerprint-moves
  ;; the hash decision-sugar-test pins for approval_request: a kind that
  ;; names no money hashes exactly as before (re-pin both together)
  (is (= "fc90652a0bca259eb600a2bdf13a7196efc99f98317613c1488445b04bd8a453"
         (fp/fingerprint-hash (r/fingerprint grants/approval-request))))
  (is (= ["+" ["data" "a"] {"dec" "0.02"}]
         (wire/form->wire (e/normalize '(+ (data :a) 0.02M))))
      "a decimal literal still crosses as its one-key node")
  (is (= {"dec" "0.02"} (#'fp/canonical-default 0.02M))))
