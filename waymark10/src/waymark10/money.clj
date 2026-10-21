(ns waymark10.money
  "Money as one value (docs/spec-ledger-money.md §2): a decimal amount
  and an ISO 4217 currency together, so `+` can refuse to add dollars
  to euros — not a pair of fields the author keeps in step.

  The law sees a Money record {:amount BigDecimal :currency \"USD\"}.
  The wire carries {\"amount\": \"18.00\", \"currency\": \"USD\"},
  the amount a STRING, so neither a float nor a client's rounding
  reaches it. The fingerprint spells a literal {\"dec\" … \"cur\" …}.
  Arithmetic never rounds: rounding is declared — (round m) to the
  currency's ISO minor unit in the house's :rounding — and a write not
  at that scale is refused, [money-unrounded].")

(set! *warn-on-reflection* true)

(defrecord Money [amount currency])

(defn money? [v] (instance? Money v))

(defn currency-code?
  "Three upper-case letters: the ISO 4217 spelling. Whether the code
  names a live currency is not this test's question."
  [s]
  (and (string? s) (boolean (re-matches #"[A-Z]{3}" s))))

(def ^:private amount-pattern #"-?[0-9]+(\.[0-9]+)?")

(defn parse-amount
  "A written amount → its exact BigDecimal; nil for anything else. A
  number is not an amount here — the wire refuses a JSON number for
  money — and an exponent (\"1e3\") is not how money is written."
  ^java.math.BigDecimal [s]
  (when (and (string? s) (re-matches amount-pattern s))
    (java.math.BigDecimal. ^String s)))

(defn money
  "The literal (money \"18.00\" \"USD\"): a Money, or nil when the
  amount is no written decimal or the currency no ISO code."
  [amount currency]
  (let [a (if (decimal? amount) amount (parse-amount amount))]
    (when (and a (currency-code? currency))
      (->Money a currency))))

(defn amount-text
  "The amount as it is written: plain digits, never an exponent."
  ^String [m]
  (.toPlainString ^java.math.BigDecimal (:amount m)))

(defn canonical-amount
  "One spelling per amount, for the law's literal: trailing zeros
  dropped, so (money \"18.00\" \"USD\") and (money \"18\" \"USD\") are
  one law. nil when `amount` is no written decimal."
  [amount]
  (when-some [^java.math.BigDecimal a (if (decimal? amount) amount (parse-amount amount))]
    (let [s (.stripTrailingZeros a)]
      (.toPlainString ^java.math.BigDecimal (if (neg? (.scale s)) (.setScale s 0) s)))))

(defn wire-node
  "The fingerprint's and the digest's spelling: {\"dec\" … \"cur\" …}."
  [m]
  {"dec" (amount-text m) "cur" (:currency m)})

(defn ->wire
  "A Money as the wire carries it."
  [m]
  {:amount (amount-text m) :currency (:currency m)})

(defn from-wire
  "A wire value → Money: {\"amount\": \"18.00\", \"currency\": \"USD\"},
  or a bare amount string where `default-currency` fills the currency.
  Anything else comes back unchanged, for validation to refuse with
  its sentence — a JSON number for the amount among them."
  [x default-currency]
  (cond
    (money? x) x
    (map? x)
    (let [a (if (contains? x :amount) (:amount x) (get x "amount"))
          c (if (contains? x :currency) (:currency x) (get x "currency"))]
      (or (when (and (= 2 (count x)) (string? a)) (money a c)) x))
    (and (string? x) default-currency) (or (money x default-currency) x)
    :else x))

(defn mixed-currency
  "The sentence for money of two currencies meeting; nil for one."
  [a b]
  (when (and (money? a) (money? b) (not= (:currency a) (:currency b)))
    (str "money in " (:currency a) " meets money in " (:currency b)
         ": the law neither adds nor compares two currencies — convert one first")))

(def division-places
  "How many places a quotient that does not terminate keeps (§2)."
  12)

(defn divide
  "a ÷ b, exactly when the quotient terminates, else to
  `division-places` places, half-even — never a non-terminating
  ArithmeticException. b is not zero: the caller asks first."
  ^java.math.BigDecimal [^java.math.BigDecimal a ^java.math.BigDecimal b]
  (try (.divide a b)
       (catch ArithmeticException _
         (.divide a b (int division-places) java.math.RoundingMode/HALF_EVEN))))

;; ── rounding (§2): declared, never implicit ─────────────────────────

(def rounding-modes
  "The house's :rounding words, each with the java mode it means."
  {:half-even java.math.RoundingMode/HALF_EVEN
   :half-up   java.math.RoundingMode/HALF_UP})

(def default-rounding
  "The house's :rounding when it declares none."
  :half-even)

(defn rounding-mode
  "The house's declared :rounding → its word: nil is the default, and
  a word `rounding-modes` does not hold is nil, for the boot to refuse."
  [r]
  (cond (nil? r) default-rounding
        (contains? rounding-modes r) r))

(defn minor-unit
  "How many places the currency's minor unit has (ISO 4217: USD 2,
  JPY 0, KWD 3), from java's own table; nil for a code it does not
  know or one with no minor unit (XXX, XAU)."
  [code]
  (when (string? code)
    (try (let [d (.getDefaultFractionDigits (java.util.Currency/getInstance ^String code))]
           (when-not (neg? d) d))
         (catch IllegalArgumentException _ nil))))

(defn round
  "m at its currency's minor unit, in `mode` (a `rounding-modes` word;
  nil reads the default). nil when the currency has no minor unit this
  engine knows."
  [m mode]
  (when-some [places (minor-unit (:currency m))]
    (->Money (.setScale ^java.math.BigDecimal (:amount m) (int places)
                        ^java.math.RoundingMode (rounding-modes (or mode default-rounding)))
             (:currency m))))

(defn rounded?
  "Is m at its currency's scale — no digit past the minor unit but
  zeros? A currency with no minor unit this engine knows is not this
  test's question: true."
  [m]
  (let [places (minor-unit (:currency m))]
    (or (nil? places)
        (<= (.scale (.stripTrailingZeros ^java.math.BigDecimal (:amount m)))
            (long places)))))

(defn unrounded
  "[money-unrounded] (§2): the sentence for a money write not at its
  currency's scale, the rounded value its remedy — both modes' when
  they differ, since a field does not know the house's; nil for
  anything else."
  [m]
  (when (and (money? m) (not (rounded? m)))
    (let [cur (:currency m)
          places (minor-unit cur)
          even (amount-text (round m :half-even))
          up (amount-text (round m :half-up))]
      (str "[money-unrounded] " (amount-text m) " " cur " is not at " cur "'s "
           places (if (= 1 places) " place" " places")
           " — wrap the expression in (round …): it rounds to "
           (if (= even up)
             (str even " " cur)
             (str even " " cur " half-even, " up " " cur " half-up"))))))
