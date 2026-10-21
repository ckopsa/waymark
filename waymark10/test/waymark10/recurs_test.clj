(ns waymark10.recurs-test
  "`:recurs` (docs/spec-calendar-time.md §4, build step 8a): the Parks'
  contract declares its visits rule, the occurrence function lists the
  visit dates with the holiday moves, a recurrence scenario judges them
  at the check tier, and the declaration battery reports the three
  findings. Nothing is armed or made here; that is step 8b."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.checks-assembly :as ca]
            [waymark10.fingerprint :as fp]
            [waymark10.recurs :as recurs]
            [waymark10.resource :as r]
            [waymark10.scenario :as scenario]
            [waymark10.server.work-calendar :as work-calendar])
  (:import (java.time LocalDate)))

(def ^:private safety
  {:idempotent true :reversible false :confirm false :one-way "Done is done."})

(def ^:private visits
  {:name :visits
   :makes :visit
   :every '(weeks 2)
   :on #{:tue}
   :from '(data :season_starts_on)
   :until '(data :season_ends_on)
   :while #{:active}
   :holidays :next
   :lead '(days 14)
   :owner '(data :account_manager_id)
   :input {:contract_id '(self)
           :visit_on '(occurrence)}})

(def ^:private contract
  {:kind :contract
   :plural "contracts"
   :states [:active :ended]
   :initial :active
   :terminal #{:ended}
   :summary "{data.customer_name} · {state}"
   :schema [:map
            [:customer_name [:string {:min 1 :max 80}]]
            [:season_starts_on :waymark/date]
            [:season_ends_on :waymark/date]
            [:account_manager_id {:optional true} [:maybe :string]]]
   :recurs [visits]
   :actions {:end {:from #{:active} :to :ended :safety safety}}})

(def ^:private visit
  {:kind :visit
   :plural "visits"
   :states [:planned :done]
   :initial :planned
   :terminal #{:done}
   :summary "{data.visit_on} · {state}"
   :schema [:map
            [:contract_id {:kind :contract} :waymark/ref]
            [:visit_on :waymark/date]]
   :actions {:finish {:from #{:planned} :to :done :safety safety}}})

(defn- world
  "The two kinds as the battery reads them; `over` merges into a
  kind's declaration."
  [& {:as over}]
  {:kinds {:contract (r/resource (merge contract (:contract over)))
           :visit (r/resource (merge visit (:visit over)))}})

(def ^:private parks
  {:customer_name "Parks" :season_starts_on "2027-04-01" :season_ends_on "2027-10-31"})

;; Memorial Day is a Monday, and the house also closes the Tuesday after
(def ^:private house
  (work-calendar/answer
   {:id "house"
    :data {:zone "America/Denver"
           :workdays ["mon" "tue" "wed" "thu" "fri"]
           :holidays [{:on "2027-05-31" :name "Memorial Day"} {:on "2027-06-01" :name "Closed"}]}}))

(defn- day [s] (LocalDate/parse s))

;; ── the dates ───────────────────────────────────────────────────────

(deftest every-second-tuesday-of-the-season
  (let [got (recurs/occurrences (assoc visits :holidays :keep) parks house)]
    (is (= 15 (count got)))
    (is (= [(day "2027-04-06") (day "2027-04-20")] (take 2 got))
        "the Tuesday of every other week, not every Tuesday")
    (is (= (day "2027-10-19") (peek got)) "the next one, 2 November, is past the season")))

(deftest the-holiday-moves
  (let [on (fn [move] (set (recurs/occurrences (assoc visits :holidays move) parks house)))]
    (testing ":keep keeps the holiday"
      (is (contains? (on :keep) (day "2027-06-01"))))
    (testing ":skip drops it"
      (is (= 14 (count (on :skip))))
      (is (not (contains? (on :skip) (day "2027-06-01")))))
    (testing ":next moves it to the next business day"
      (is (= 15 (count (on :next))))
      (is (contains? (on :next) (day "2027-06-02"))))
    (testing ":previous walks back past Memorial Day to the Friday"
      (is (contains? (on :previous) (day "2027-05-28"))))
    (testing ":skip is the default"
      (is (= (on :skip) (set (recurs/occurrences (dissoc visits :holidays) parks house))))))
  (testing "with no calendar no date is a holiday"
    (is (contains? (set (recurs/occurrences visits parks nil)) (day "2027-06-01")))))

(deftest a-count-and-a-month
  (testing "two visits a week, twelve in all"
    (let [got (recurs/occurrences {:name :sessions :makes :visit :every '(weeks 1) :on #{:tue :fri}
                                   :from '(data :starts_on) :times 12}
                                  {:starts_on "2027-04-01"} house)]
      (is (= 12 (count got)))
      (is (= [(day "2027-04-02") (day "2027-04-06")] (take 2 got)))
      (is (= (day "2027-05-11") (peek got)))))
  (let [close {:name :close :makes :visit :every '(months 1) :from '(date "2027-01-31") :while #{:open}}]
    (testing "a monthly close keeps to the month's end"
      (is (= (mapv day ["2027-01-31" "2027-02-28" "2027-03-31"])
             (recurs/occurrences (assoc close :times 3) {} nil))))
    (testing "the next occurrences on or after a day"
      (is (= (mapv day ["2027-06-30" "2027-07-31"])
             (recurs/next-occurrences close {} nil (day "2027-06-15") 2))))
    (testing "a rule kept going only by :while still answers a bounded list"
      (is (= 1000 (count (recurs/occurrences close {} nil)))))))

(deftest no-start-no-list
  (is (= [] (recurs/occurrences visits {} house)))
  (is (= [] (recurs/occurrences (assoc visits :every '(hours 2)) parks house))))

;; ── the recurrence scenario ─────────────────────────────────────────

(defn- parks-visits [expect]
  (scenario/scenario :parks-visits "The Parks are visited every second Tuesday of the season."
                     {:kind :contract :recurs :visits
                      :row {:state :active :data parks}
                      :calendar {:zone "America/Denver"
                                 :workdays #{:mon :tue :wed :thu :fri}
                                 :holidays ["2027-06-01"]}
                      :expect {:occurrences expect}}))

(deftest a-recurrence-scenario-is-judged-at-the-check-tier
  (let [rdef (r/resource contract)]
    (is (scenario/check-tier? rdef (parks-visits {:count 15})))
    (is (= [] (scenario/judge rdef (parks-visits {:first "2027-04-06" :last "2027-10-19" :count 15}))))
    (testing "the closed Tuesday moves to the Wednesday"
      (is (= [] (scenario/judge rdef (parks-visits {:first "2027-04-06" :count 15})))))
    (testing "a broken promise reads as its sentence"
      (let [[v] (scenario/judge rdef (parks-visits {:count 16}))]
        (is (str/includes? v "The Parks are visited every second Tuesday"))
        (is (str/includes? v "count 15, not 16"))))
    (testing "a rule the kind does not declare"
      (is (str/includes? (first (scenario/judge rdef (assoc (parks-visits {:count 15}) :recurs :mowing)))
                         "which is no rule of contract")))
    (testing "the report counts it as checked"
      (let [{:keys [checked violations]} (scenario/report (assoc rdef :scenarios [(parks-visits {:count 15})]))]
        (is (= 1 checked))
        (is (= [] violations)))))
  (testing "a recurrence scenario attempts no door"
    (is (thrown? Exception (scenario/scenario :x "The Parks are visited."
                                              {:kind :contract :recurs :visits :attempt :end
                                               :expect {:occurrences {:count 15}}}))))
  (testing "its verdict is the list"
    (is (thrown? Exception (parks-visits {:first "April"})))
    (is (thrown? Exception (scenario/scenario :x "The Parks are visited."
                                              {:kind :contract :recurs :visits :expect {:allowed true}})))))

;; ── the law ─────────────────────────────────────────────────────────

(deftest the-rule-is-law
  (let [hash-of #(fp/fingerprint-hash (fp/fingerprint-of (r/resource %)))]
    (is (= "visits" (first (keys (get (fp/fingerprint-of (r/resource contract)) "recurs")))))
    (is (not (contains? (fp/fingerprint-of (r/resource (dissoc contract :recurs))) "recurs"))
        "a kind without rules hashes as before")
    (is (not= (hash-of contract)
              (hash-of (assoc contract :recurs [(assoc visits :every '(weeks 3))])))
        "a change to the rule is a revision")
    (is (= :truth (fp/classify-path "recurs.visits.every")))))

;; ── the battery ─────────────────────────────────────────────────────

(defn- refused [over]
  (try (ca/run-all (world :contract over)) nil
       (catch Exception e (ex-message e))))

(deftest the-findings
  (testing "the contract world assembles"
    (is (map? (ca/run-all (world)))))
  (testing "[recurs-no-owner]"
    (is (re-find #"contract \[recurs-no-owner\].*Whose name goes on each visit this makes\?"
                 (str (refused {:recurs [(dissoc visits :owner)]})))))
  (testing "[recurs-no-stop]"
    (is (re-find #"contract \[recurs-no-stop\].*When should the visits stop coming\?"
                 (str (refused {:recurs [(dissoc visits :until :while)]}))))
    (is (nil? (refused {:recurs [(-> visits (dissoc :until :while) (assoc :times 12))]}))
        "a count is a stop"))
  (testing "[recurs-unmakeable]: the input misses a required create field"
    (is (re-find #"contract \[recurs-unmakeable\].*misses visit_on"
                 (str (refused {:recurs [(update visits :input dissoc :visit_on)]})))))
  (testing "[recurs-unmakeable]: a kind the registry does not hold"
    (is (re-find #"contract \[recurs-unmakeable\].*does not hold"
                 (str (refused {:recurs [(assoc visits :makes :mowing)]})))))
  (testing "[recurs]: the rule's shape"
    (is (re-find #"contract \[recurs\].*:on is a set of weekdays"
                 (str (refused {:recurs [(assoc visits :on #{:tuesday})]}))))
    (is (re-find #"contract \[recurs\].*:while is a set of contract's states"
                 (str (refused {:recurs [(assoc visits :while #{:signed})]}))))
    (is (re-find #"contract \[recurs\].*keys a rule does not know"
                 (str (refused {:recurs [(assoc visits :every-other true)]}))))))
