(ns waymark10.reconciles-test
  "Ledger step 9d (docs/spec-ledger-money.md §7): a close kind's
  :reconciles names two sides over (balance …) and a period, and the
  engine projects each side and their gap as read-only facts on the
  close row, summed from the postings effective in the period — nobody
  types a total."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.bounds :as bounds]
            [waymark10.expr :as expr]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.money :as money]
            [waymark10.resource :as r]
            [waymark10.scenario :as scenario]
            [waymark10.types :as t]))

(def ^:private sides
  '{:processor_in (balance [:house :processor_in])
    :expected (- (- (balance [:all :rc_order :paid])
                    (balance [:all :rc_order :refunded]))
                 (balance [:house :fees]))})

(defn- close-map [reconciles & {:as overrides}]
  (merge {:kind :rc_month_close
          :plural "rc_month_closes"
          :states [:open :closed]
          :initial :open
          :terminal #{:closed}
          :summary "{data.period_start} · {state}"
          :schema [:map [:period_start :waymark/date] [:period_end :waymark/date]]
          :reconciles (merge {:period '[(data :period_start) (data :period_end)]
                              :sides sides
                              :tolerance '(money "0.00" "USD")}
                             reconciles)
          :actions {:close {:from #{:open} :to :closed
                            :safety {:idempotent false :reversible false :confirm false
                                     :one-way "A closed month is the test's last word."}
                            :display {:label "Close"}}}}
         overrides))

(def ^:private month-close (r/resource (close-map {})))

(def ^:private postings
  [[[:house :processor_in] "4210.00 USD" "2026-10-05"]
   [[:house :processor_in] "99.00 USD" "2026-11-02"]
   [[:rc_order "o1" :paid] "3000.00 USD" "2026-10-03"]
   [[:rc_order "o2" :paid] "1400.00 USD" "2026-10-12"]
   [[:rc_order "o2" :paid] "500.00 USD" "2026-09-30"]
   [[:rc_order "o1" :refunded] "40.00 USD" "2026-10-20"]
   [[:house :fees] "110.00 USD" "2026-10-31"]])

(defn- usd= [s v] (and (money/money? v) (= "USD" (:currency v))
                       (zero? (compare (bigdec s) (:amount v)))))

(defn- fact [rdef f row ctx]
  ((get-in rdef [:computed f :fn]) row ctx))

(deftest step-9d-sides-and-gap-from-seeded-postings
  (let [ctx {:balance-over (:balance-over (#'scenario/scenario-ledger {:postings postings}))}
        row {:id "oct" :state :open :data {:period_start "2026-10-01" :period_end "2026-11-01"}}]
    (testing "each side and the gap is a read-only fact of the close"
      (is (= #{:processor_in :expected :gap}
             (set (filter #{:processor_in :expected :gap} (keys (:computed month-close))))))
      (is (every? #(get-in month-close [:computed % :reads?]) [:processor_in :expected :gap])))
    (testing "summed over the period only: what fell outside it does not count"
      (is (usd= "4210.00" (fact month-close :processor_in row ctx)))
      (is (usd= "4250.00" (fact month-close :expected row ctx)))
      (is (usd= "-40.00" (fact month-close :gap row ctx))))
    (testing "an unset period, or no ledger to ask, is no answer — never zero"
      (is (nil? (fact month-close :gap (update row :data dissoc :period_end) ctx)))
      (is (nil? (fact month-close :gap row {}))))
    (testing "what the close closes is the accounts its sides name"
      (is (= #{[:house :processor_in] [:all :rc_order :paid]
               [:all :rc_order :refunded] [:house :fees]}
             (:closes month-close))))))

(deftest step-9d-no-door-input-names-a-fact
  (is (thrown-with-msg? Exception #"input names gap"
                        (r/resource (assoc-in (close-map {}) [:actions :close :input]
                                              [:map [:gap :waymark/money]]))))
  (is (thrown-with-msg? Exception #"expected is also a field"
                        (r/resource (update (close-map {}) :schema conj
                                            [:expected :waymark/money])))))

(deftest step-9d-def-time-refusals
  (testing "exactly two sides"
    (is (thrown-with-msg? Exception #"exactly two :sides"
                          (r/resource (close-map {:sides (assoc sides :third '(balance [:house :fees]))})))))
  (testing "one unit across the sides and the tolerance"
    (is (thrown-with-msg? Exception #"mixes units"
                          (r/resource (close-map {:sides (assoc sides :processor_in
                                                                '(+ (balance [:house :processor_in])
                                                                    (money "0.00" "USD")))
                                                  :tolerance '(money "0.00" "EUR")})))))
  (testing "a period is two date expressions"
    (is (thrown-with-msg? Exception #"takes :period"
                          (r/resource (update (close-map {}) :reconciles dissoc :period)))))
  (testing "a side is a sum of postings"
    (is (thrown-with-msg? Exception #"reads no \(balance"
                          (r/resource (close-map {:sides (assoc sides :processor_in
                                                                '(money "0.00" "USD"))}))))))

(deftest step-9d-the-declaration-is-law
  (let [h (comp fp/fingerprint-hash fp/fingerprint-of)]
    (is (contains? (fp/fingerprint-of month-close) "reconciles"))
    (is (not= (h month-close)
              (h (r/resource (close-map {:tolerance '(money "1.00" "USD")})))))))

;; ── ledger step 9e: a close lands only when its sides agree ─────────────

(def ^:private derived
  (:rc_month_close (bounds/derive-kinds {:rc_month_close month-close})))

(def ^:private close-guard
  (some #(when (= :rc_month_close.gap-within-tolerance (:name %)) %)
        (get-in derived [:actions :close :guards])))

(def ^:private october
  {:id "oct" :state :open :data {:period_start "2026-10-01" :period_end "2026-11-01"}})

(defn- ledger [ps] (#'scenario/scenario-ledger {:postings ps}))

(deftest step-9e-the-close-door-gains-the-guard
  (testing "the close door carries it, and deriving twice is deriving once"
    (is (some? close-guard))
    (is (= derived (:rc_month_close (bounds/derive-kinds {:rc_month_close derived})))))
  (testing "within tolerance it passes"
    (let [[v] (g/evaluate close-guard october {}
                          (ledger (conj postings [[:house :processor_in] "40.00 USD" "2026-10-29"])))]
      (is (t/allow? v))))
  (testing "a $40.00 gap is refused, its evidence naming the order the refund came from"
    (let [[v] (g/evaluate close-guard october {} (ledger postings))]
      (is (t/deny? v))
      (is (= ["house/processor_in"] (map :account (get-in v [:evidence :processor_in]))))
      (is (= ["rc_order/o1/paid" "rc_order/o2/paid" "house/fees" "rc_order/o1/refunded"]
             (map :account (get-in v [:evidence :expected]))))
      (is (= {:amount "40.00" :currency "USD"}
             (:amount (last (get-in v [:evidence :expected])))))))
  (testing "at most five postings a side, the largest first"
    (let [fees (for [i (range 7)] [[:house :fees] (str (inc i) ".00 USD") "2026-10-15"])
          [v] (g/evaluate close-guard october {} (ledger (into postings fees)))
          shown (get-in v [:evidence :expected])]
      (is (t/deny? v))
      (is (= ["3000.00" "1400.00" "110.00" "40.00" "7.00"] (map (comp :amount :amount) shown)))))
  (testing "on the probe, with no ledger to ask, it is unknown: pending, not refused"
    (is (expr/unknown? (expr/evaluate (:when close-guard) {:data (:data october)})))
    (is (not (t/deny? (first (g/evaluate close-guard october {} {})))))))

(deftest step-9e-a-scenario-expects-the-close-refused
  (is (= [] (scenario/judge derived
                            (scenario/scenario :rc-close "A month with a deposit missing does not close."
                                               {:kind :rc_month_close :attempt :close
                                                :row {:state :open :data (:data october)}
                                                :postings postings
                                                :expect {:refused :rc_month_close.gap-within-tolerance
                                                         :because "Processor in is 4210.00 USD and expected is 4250.00 USD: 40.00 USD apart"}})))))
