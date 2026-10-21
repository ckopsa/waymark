(ns waymark10.maintainer-calendar-test
  "Clock facts in the house's zone (docs/spec-calendar-time.md §2, §11
  step 4): a fact that reads :today flips at the governing calendar's
  midnight, and the field-plus-period shape is a flip candidate. Pure:
  no database, the maintainer's index read alone."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.derived :as derived]
            [waymark10.resource :as r]
            [waymark10.server.maintainer :as maint])
  (:import (java.time Instant LocalDate ZoneId)))

(def ^:private invoice
  (r/resource
   {:kind :mcal_invoice
    :plural "mcal_invoices"
    :states [:open :paid]
    :initial :open
    :terminal #{:paid}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:issued_on [:maybe :waymark/date]]
             [:overdue {:optional true} [:maybe :boolean]]]
    :derived {:overdue {:over [:issued_on :today]
                        :expr '(< (+ (var :issued_on) (days 30)) (var :today))}}
    :actions {:pay {:from #{:open} :to :paid
                    :safety {:idempotent true :reversible false
                             :confirm false
                             :one-way "Paid is history."}}}}))

(def ^:private denver
  (fn [& _] {:id "elm" :version 1 :zone (ZoneId/of "America/Denver")
             :workdays #{:mon :tue :wed :thu :fri} :holidays #{}}))

(def ^:private data {:title "Mulch" :issued_on (LocalDate/parse "2026-10-01")})

(defn- overdue-at [^Instant t]
  (:overdue (derived/compute-facts (:derived invoice) data t denver)))

(deftest net-30-overdue-flips-at-the-denver-midnight
  (let [now (Instant/parse "2026-10-31T12:00:00Z")
        flip (Instant/parse "2026-11-01T06:00:00Z")]   ; Denver's midnight, MDT
    (testing "the index lands on the house's midnight after the due date"
      (is (= flip (maint/next-flip-at invoice nil data now denver))))
    (testing "and the fact really flips there, not at UTC midnight"
      (is (false? (overdue-at (Instant/parse "2026-11-01T00:00:00Z"))))
      (is (false? (overdue-at (.minusMillis flip 1))))
      (is (true? (overdue-at flip))))
    (testing "past the flip, the fact can no longer change without a write"
      (is (nil? (maint/next-flip-at invoice nil data flip denver))))
    (testing "with no calendar the house's day is unknown: re-check, never UTC"
      (is (= (.plusMillis now maint/recheck-interval-ms)
             (maint/next-flip-at invoice nil data now nil))))))
