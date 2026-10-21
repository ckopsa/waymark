(ns waymark10.scenario-calendar-test
  "Scenario :calendar (docs/spec-calendar-time.md §6, build step 6): a
  scenario names the house's calendar beside its moment, offline-ctx
  serves the :calendar hook from it, and calendar-reading law is judged
  at the check tier — or, with no :calendar named, refused as a
  missing :at is."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.declare :refer [defguard refuse]]
            [waymark10.resource :as r]
            [waymark10.scenario :as scenario]))

(defguard sc-opens-on-a-business-day
  (refuse "The shop opens on a business day, and today is not one.")
  '(business-day? (today))
  {:remedies [:approval_request/create]})

(def ^:private shift
  (delay
    (r/resource
     {:kind :sc_shift
      :plural "sc_shifts"
      :states [:closed :open]
      :initial :closed
      :terminal #{:open}
      :summary "{data.title} · {state}"
      :schema [:map [:title [:string {:min 1 :max 40}]]]
      :actions {:open {:from #{:closed} :to :open
                       :guards [sc-opens-on-a-business-day]
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Open is open."}}}})))

(def ^:private denver
  {:zone "America/Denver"
   :workdays #{:mon :tue :wed :thu :fri}
   :hours {:from "07:00" :to "17:00"}
   :holidays ["2027-05-31"]})

(defn- opening
  [at calendar expect]
  (scenario/scenario :opening "The shop opens on a business day."
                     (cond-> {:kind :sc_shift :attempt :open
                              :row {:state :closed :data {:title "Monday"}}
                              :as {:id "mom" :type :person}
                              :at at
                              :expect expect}
                       calendar (assoc :calendar calendar))))

(defn- refusal [f]
  (try (f) nil (catch Exception e (ex-message e))))

(deftest the-scenario-serves-the-calendar
  (testing "a holiday is no business day"
    (let [s (opening "2027-05-31T16:00:00Z" denver {:refused :sc-opens-on-a-business-day})]
      (is (scenario/check-tier? @shift s) "judged here, not deferred")
      (is (= [] (scenario/judge @shift s)))))
  (testing "the day after it is"
    (is (= [] (scenario/judge @shift (opening "2027-06-01T16:00:00Z" denver {:allowed true})))))
  (testing "the day is the house's, not Greenwich's"
    (is (= [] (scenario/judge @shift (opening "2027-06-01T03:00:00Z" denver
                                              {:refused :sc-opens-on-a-business-day})))
        "still 31 May in Denver")
    (is (= [] (scenario/judge @shift (opening "2027-06-01T03:00:00Z"
                                              (assoc denver :zone "UTC")
                                              {:allowed true})))))
  (testing "a holiday spelled with its name"
    (is (= [] (scenario/judge @shift (opening "2027-05-31T16:00:00Z"
                                              (assoc denver :holidays [{:on "2027-05-31" :name "Memorial Day"}])
                                              {:refused :sc-opens-on-a-business-day})))))
  (testing "a calendar that works Mondays only"
    (is (= [] (scenario/judge @shift (opening "2027-06-01T16:00:00Z"
                                              (assoc denver :workdays #{:mon})
                                              {:refused :sc-opens-on-a-business-day}))))))

(deftest calendar-law-without-a-calendar-is-refused
  (let [s (opening "2027-05-31T16:00:00Z" nil {:allowed true})]
    (is (str/includes? (first (scenario/judge @shift s)) "declares no :calendar"))))

(deftest a-malformed-calendar-refuses-where-it-is-written
  (testing "the key set is closed"
    (is (str/includes? (refusal #(opening "2027-05-31T16:00:00Z" (assoc denver :holiday []) {:allowed true}))
                       "not calendar keys")))
  (testing "the zone names a zone"
    (is (str/includes? (refusal #(opening "2027-05-31T16:00:00Z" (assoc denver :zone "Mars/Olympus") {:allowed true}))
                       "names no time zone")))
  (testing "the workdays are weekdays"
    (is (str/includes? (refusal #(opening "2027-05-31T16:00:00Z" (assoc denver :workdays #{:funday}) {:allowed true}))
                       "funday")))
  (testing "the hours are clock times"
    (is (str/includes? (refusal #(opening "2027-05-31T16:00:00Z" (assoc denver :hours {:from "7am" :to "5pm"}) {:allowed true}))
                       ":hours")))
  (testing "a holiday is a date"
    (is (str/includes? (refusal #(opening "2027-05-31T16:00:00Z" (assoc denver :holidays ["Memorial Day"]) {:allowed true}))
                       "not a date"))))
