(ns waymark10.recurs-arming-test
  "`:recurs` arming (docs/spec-calendar-time.md §4, build step 8b): a
  contract's visits rule keeps one scheduled action outstanding, each
  run makes one visit under its `recurs:` key and stops at the season's
  end, a retried run makes nothing new, the run reads the contract
  again, and leaving the rule's :while states cancels what is
  outstanding. Memory storage, the real engine and a clock a test moves
  by hand."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private safety
  {:idempotent true :reversible true :confirm false})

(def ^:private visits
  {:name :visits
   :makes :arming_visit
   :every '(weeks 1)
   :from '(data :season_starts_on)
   :until '(data :season_ends_on)
   :while #{:active}
   :lead '(days 2)
   :owner '(data :account_manager_id)
   :input {:contract_id '(self)
           :visit_on '(occurrence)}})

(def ^:private contract
  (r/resource
   {:kind :arming_contract
    :plural "arming_contracts"
    :states [:active :paused :ended]
    :initial :active
    :terminal #{:ended}
    :summary "{data.customer_name} · {state}"
    :schema [:map
             [:customer_name [:string {:min 1 :max 80}]]
             [:season_starts_on :waymark/date]
             [:season_ends_on :waymark/date]
             [:account_manager_id {:optional true} [:maybe :string]]]
    :recurs [visits]
    :actions {:pause {:from #{:active} :to :paused :safety safety}
              :resume {:from #{:paused} :to :active :safety safety}
              :end {:from #{:active :paused} :to :ended
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "An ended contract makes no more visits."}}}}))

(def ^:private visit
  (r/resource
   {:kind :arming_visit
    :plural "arming_visits"
    :states [:planned :done]
    :initial :planned
    :terminal #{:done}
    :summary "{data.visit_on} · {state}"
    :schema [:map
             [:contract_id {:kind :arming_contract} :waymark/ref]
             [:visit_on :waymark/date]]
    :actions {:finish {:from #{:planned} :to :done
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "A visit made is made."}}}}))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(defn- world
  "An engine whose clock is the atom's instant, on Thursday 1 April
  2027, and the member the rule runs as."
  []
  (let [clock (atom (Instant/parse "2027-04-01T12:00:00Z"))
        eng (engine/engine {:storage (memory/storage)
                            :resources [contract visit]
                            :now-fn (fn [] @clock)})]
    (inv/create! eng :member {:display "colton" :actor_type "human"}
                 {:principal scheduled/engine-actor :id "colton"})
    {:eng eng :clock clock}))

(defn- at! [clock s] (reset! clock (Instant/parse s)))

(defn- rows-of [eng kind]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (store/with-tx st
      (fn [tx]
        (mapv #(inv/decode-row rdef %)
              (store/query-rows st tx kind {} {:limit 100}))))))

(defn- action-of [eng id]
  (first (filter #(= (str id) (str (:id %))) (rows-of eng :scheduled_action))))

(defn- open-actions [eng]
  (filterv #(#{:scheduled :proposed} (:state %)) (rows-of eng :scheduled_action)))

(defn- armed-dates [eng]
  (mapv #(get-in % [:data :recurrence :occurs_on]) (open-actions eng)))

(defn- visit-dates [eng]
  (vec (sort (map #(str (get-in % [:data :visit_on])) (rows-of eng :arming_visit)))))

(defn- contract!
  "The Parks' contract: four Monday visits, 5 to 26 April."
  [eng]
  (str (:id (:row (inv/create! eng :arming_contract
                               {:customer_name "Parks"
                                :season_starts_on "2027-04-05"
                                :season_ends_on "2027-04-26"
                                :account_manager_id "colton"}
                               {:principal colton})))))

(deftest a-contract-arms-its-next-visit
  (let [{:keys [eng]} (world)
        c (contract! eng)
        [a & more] (open-actions eng)]
    (is (some? a))
    (is (nil? more) "one action outstanding per row and rule")
    (is (= {:source_kind "arming_contract" :source_id c :rule "visits" :occurs_on "2027-04-05"}
           (get-in a [:data :recurrence])))
    (is (= (str "recurs:" c ":visits") (get-in a [:data :recurs_series])))
    (is (= ["arming_visit" "create"]
           ((juxt :kind :action) (get-in a [:data :target]))))
    (is (= {:contract_id c :visit_on "2027-04-05"} (get-in a [:data :input])))
    (testing "two days ahead of the visit, at midnight with no calendar"
      (is (= (Instant/parse "2027-04-03T00:00:00Z") (get-in a [:data :run_at]))))
    (testing "it runs as the rule's owner"
      (is (= "colton" (get-in a [:data :acts_as :id]))))))

(deftest each-run-makes-one-visit-until-the-season-ends
  (let [{:keys [eng clock]} (world)
        c (contract! eng)]
    (doseq [run ["2027-04-03T00:00:30Z" "2027-04-10T00:00:30Z"
                 "2027-04-17T00:00:30Z" "2027-04-24T00:00:30Z"]]
      (at! clock run)
      (scheduled/sweep-due! eng))
    (is (= ["2027-04-05" "2027-04-12" "2027-04-19" "2027-04-26"] (visit-dates eng)))
    (testing "each made row keeps the key it was made under"
      (is (= (set (map #(str "recurs:" c ":visits:" %) (visit-dates eng)))
             (set (map #(get-in % [:data :recurs_from]) (rows-of eng :arming_visit))))))
    (testing "the season's end stops it: nothing is outstanding"
      (is (empty? (open-actions eng))))))

(deftest a-retried-run-makes-nothing-new
  (let [{:keys [eng clock]} (world)
        c (contract! eng)
        a (:id (first (open-actions eng)))
        k (str "recurs:" c ":visits:2027-04-05")]
    (at! clock "2027-04-03T00:00:30Z")
    (is (some? (scheduled/start! eng a)))
    ;; the call landed before the engine stopped: the visit is there
    ;; under the occurrence's key, and the row was left running
    (inv/create! eng :arming_visit {:contract_id c :visit_on "2027-04-05"}
                 {:principal colton :idempotency-key k :recurs-from k})
    (is (= :done (scheduled/run! eng a)))
    (is (= ["2027-04-05"] (visit-dates eng)) "the retry made no second visit")
    (testing "the next occurrence is armed, and not this one again"
      (is (= ["2027-04-12"] (armed-dates eng))))
    (testing "re-arming skips a date its key already made"
      (inv/invoke! eng :arming_contract c :pause {} {:principal colton})
      (inv/invoke! eng :arming_contract c :resume {} {:principal colton})
      (is (= ["2027-04-12"] (armed-dates eng)))
      (is (= ["2027-04-05"] (visit-dates eng))))))

(deftest leaving-the-while-states-cancels-the-outstanding-action
  (let [{:keys [eng]} (world)
        c (contract! eng)
        a (:id (first (open-actions eng)))]
    (inv/invoke! eng :arming_contract c :pause {} {:principal colton})
    (is (empty? (open-actions eng)))
    (is (= :cancelled (:state (action-of eng a))))
    (testing "coming back arms the same occurrence again"
      (inv/invoke! eng :arming_contract c :resume {} {:principal colton})
      (is (= ["2027-04-05"] (armed-dates eng))))
    (testing "ending the contract cancels it for good"
      (inv/invoke! eng :arming_contract c :end {} {:principal colton})
      (is (empty? (open-actions eng))))))

(deftest the-run-reads-the-contract-again
  (let [{:keys [eng clock]} (world)
        c (contract! eng)
        a (:id (first (open-actions eng)))]
    ;; a write the arming seam never saw
    (inv/invoke! (dissoc eng :maintain) :arming_contract c :pause {} {:principal colton})
    (at! clock "2027-04-03T00:00:30Z")
    (scheduled/sweep-due! eng)
    (let [row (action-of eng a)]
      (is (= :skipped (:state row)))
      (is (re-find #"rule visits .* is paused" (str (get-in row [:data :outcome_why])))))
    (is (empty? (visit-dates eng)))
    (is (empty? (open-actions eng)) "a paused contract arms nothing next")))
