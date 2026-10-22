(ns waymark10.calendar-ages-test
  "Calendar step 5 (docs/spec-calendar-time.md §2 `:expected-age` from a
  date, §3, §7): the live pass ages a row by a date it holds and in
  business days, the gate takes those ages, [date-unread] lists a date
  nothing reads, and the cracks audit names a deadline nobody hears."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.checks-assembly :as ca]
            [waymark10.resource :as r]
            [waymark10.scenario :as scenario]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.live-cracks :as live])
  (:import (java.time Instant LocalDate ZoneId)))

(def ^:private denver
  {:zone (ZoneId/of "America/Denver")
   :workdays #{:mon :tue :wed :thu :fri}
   :holidays {(LocalDate/parse "2026-10-12") "Founders' Day"}})

(defn- calendar [_] denver)

(def ^:private invoice
  {:kind :invoice
   :states [:draft :sent :in_billing :paid]
   :initial :draft
   :terminal #{:paid}
   :expected-age {:sent {:until '(+ (data :due_on) (days 15))}
                  :in_billing {:business-days 2}}
   :actions {:send {:from #{:draft} :to :sent}
             :bill {:from #{:sent} :to :in_billing}
             :pay {:from #{:sent :in_billing} :to :paid}}})

(defn- aged [rows now cal]
  (mapv :id (live/aged-rows [invoice] [] {:invoice rows} (Instant/parse now) cal)))

(deftest an-until-age-counts-from-a-date-in-the-row
  (let [rows [{:id "due" :state :sent :updated-at "2026-09-01T00:00:00Z"
               :data {:due_on "2026-09-20"}}
              {:id "unset" :state :sent :updated-at "2026-09-01T00:00:00Z"
               :data {}}]]
    (testing "a date is overdue once its day ends in the calendar's zone, not before"
      ;; 2026-09-20 + 15 days is 2026-10-05; it ends at 06:00Z in Denver
      (is (= [] (aged rows "2026-10-06T05:59:00Z" calendar)))
      (is (= ["due"] (aged rows "2026-10-06T06:01:00Z" calendar))))
    (testing "the crack names its deadline"
      (let [[c] (live/aged-rows [invoice] [] {:invoice rows}
                                (Instant/parse "2026-10-07T00:00:00Z") calendar)]
        (is (= "2026-10-06T06:00:00Z" (:due c)))
        (is (str/includes? (:what c) "past its deadline of 2026-10-06T06:00:00Z"))))
    (testing "with no calendar a date has no zone, and nothing is aged"
      (is (= [] (aged rows "2026-12-01T00:00:00Z" nil))))))

(deftest a-business-days-age-skips-weekends-and-holidays
  ;; entered Friday 09:00 in Denver; Monday is a holiday, so two
  ;; business days run to Wednesday 09:00
  (let [rows [{:id "b" :state :in_billing :updated-at "2026-10-09T15:00:00Z" :data {}}]]
    (is (= [] (aged rows "2026-10-14T14:59:00Z" calendar)))
    (is (= ["b"] (aged rows "2026-10-14T15:01:00Z" calendar)))
    (is (= [] (aged rows "2026-10-20T00:00:00Z" nil)) "no calendar, no business days")))

(deftest a-map-age-counts-as-its-nominal-seconds-elsewhere
  (is (= 172800 (live/expected-age invoice :in_billing)))
  (is (= 86400 (live/expected-age invoice :sent)))
  (is (nil? (live/expected-age invoice :draft))))

(def ^:private base
  {:kind :thing
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:max 100}]]
            [:due_on {:optional true} [:maybe :waymark/date]]]
   :actions {:close {:from #{:open} :to :closed
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Closing is cheap."}}}})

(defn- load-quietly [m]
  (binding [*err* (java.io.StringWriter.)]
    (r/resource m)))

(defn- refused-by [m]
  (try (load-quietly m) nil
       (catch clojure.lang.ExceptionInfo e (:check (ex-data e)))))

(deftest the-gate-takes-a-date-and-a-business-days-age
  (is (some? (load-quietly (assoc base :expected-age {:open {:until '(+ (data :due_on) (days 15))}}))))
  (is (some? (load-quietly (assoc base :expected-age {:open {:business-days 2}}))))
  (doseq [age [{:business-days 0}
               {:business-days 2 :until '(data :due_on)}
               {:soon 1}
               {:until '(+ (now) (days 1))}
               {:until '(+ (today) (days 1))}
               {:until '(+ (input :due_on) (days 1))}
               {:until '(frobnicate (data :due_on))}]]
    (is (= :expected-age (refused-by (assoc base :expected-age {:open age})))
        (pr-str age))))

(deftest an-age-on-the-calendar-is-a-calendar-reader
  (let [readers #(ca/calendar-readers
                  {:kinds {:thing (load-quietly (assoc base :expected-age {:open %}))}})]
    (testing "business days and a date are counted on the default calendar"
      (is (= ["thing open :expected-age"] (readers {:business-days 2})))
      (is (= ["thing open :expected-age"]
             (readers {:until '(+ (data :due_on) (days 15))}))))
    (testing "seconds need no calendar"
      (is (= [] (readers 86400))))))

(defn- date-unread [m]
  (filterv #(str/starts-with? % "[date-unread]")
           (:waymark10/info (meta (load-quietly m)))))

(deftest a-date-nothing-reads-is-listed-as-info
  (testing "a date no guard, fact or age reads"
    (is (= 1 (count (date-unread base))))
    (is (str/includes? (first (date-unread base)) "field due_on")))
  (testing "an age that reads it"
    (is (= [] (date-unread (assoc base :expected-age
                                  {:open {:until '(+ (data :due_on) (days 15))}})))))
  (testing "a field that says it is kept, not acted on"
    (is (= [] (date-unread (assoc base :schema
                                  [:map
                                   [:name [:string {:max 100}]]
                                   [:due_on {:optional true :record-only true}
                                    [:maybe :waymark/date]]]))))))

(defn- untold [world]
  (->> (cracks/audit [invoice] [] world)
       (filter #(= "deadline_untold" (:crack %)))
       (mapv (juxt :kind :state :fields))))

(deftest a-deadline-nobody-hears-is-a-crack
  (testing "no seat leaves sent and nothing tells a person"
    (is (= [["invoice" "sent" ["due_on"]]] (untold nil))))
  (testing "a notice rule on the state tells someone"
    (is (= [] (untold {:notice-rules [{:data {:kind "invoice"
                                              :when {:to_state "sent"}}}]})))))

;; ── the age scenario (ticket 4b20e9e1) ──────────────────────────────

(def ^:private house
  {:zone "America/Denver" :workdays #{:mon :tue :wed :thu :fri} :holidays ["2026-10-12"]})

(defn- age-scenario [m]
  (scenario/scenario :invoice-age "An invoice is chased once its grace runs out."
                     (merge {:kind :invoice :calendar house} m)))

(deftest an-age-scenario-is-judged-at-the-check-tier
  (let [sent {:age :sent :row {:state :sent :data {:due_on "2026-09-20"}}}]
    (is (scenario/check-tier? invoice (age-scenario (assoc sent :expect {:due "2026-10-05"}))))
    (testing "a date age is due through its last day, in the calendar's zone"
      (is (= [] (scenario/judge invoice (age-scenario (assoc sent :expect {:due "2026-10-05"})))))
      (is (= [] (scenario/judge invoice (age-scenario (assoc sent :expect {:due "2026-10-06T06:00:00Z"}))))))
    (testing "late is judged at :at, as the live pass judges it"
      (is (= [] (scenario/judge invoice (age-scenario (assoc sent :at "2026-10-06T05:59:00Z"
                                                                 :expect {:late false})))))
      (is (= [] (scenario/judge invoice (age-scenario (assoc sent :at "2026-10-06T06:01:00Z"
                                                                 :expect {:late true}))))))
    (testing "a broken promise reads as its sentence"
      (let [[v] (scenario/judge invoice (age-scenario (assoc sent :expect {:due "2026-10-04"})))]
        (is (str/includes? v "An invoice is chased once its grace runs out."))
        (is (str/includes? v "due \"2026-10-05\", not \"2026-10-04\""))))
    (testing "with no calendar a date has no zone"
      (is (str/includes? (first (scenario/judge invoice (-> (age-scenario (assoc sent :expect {:due "2026-10-05"}))
                                                            (dissoc :calendar))))
                         "no :calendar"))))
  (testing "business days skip the weekend and the holiday"
    (let [billing {:age :in_billing :entered "2026-10-09T15:00:00Z" :row {:state :in_billing :data {}}}]
      (is (= [] (scenario/judge invoice (age-scenario (assoc billing :expect {:due "2026-10-14"})))))
      (is (= [] (scenario/judge invoice (age-scenario (assoc billing :at "2026-10-14T15:01:00Z"
                                                                    :expect {:late true})))))
      (is (str/includes? (first (scenario/judge invoice (age-scenario (-> billing (dissoc :entered)
                                                                            (assoc :expect {:due "2026-10-14"})))))
                         "counts from :entered"))))
  (testing "a state with no dated age"
    (is (str/includes? (first (scenario/judge invoice (age-scenario {:age :draft :expect {:due "2026-10-05"}})))
                       "which has no dated :expected-age on invoice")))
  (testing "an age scenario attempts no door, and its verdict is the deadline"
    (is (thrown? Exception (age-scenario {:age :sent :attempt :pay :expect {:due "2026-10-05"}})))
    (is (thrown? Exception (age-scenario {:age :sent :expect {:allowed true}})))
    (is (thrown? Exception (age-scenario {:age :sent :expect {:due "October"}})))
    (is (thrown? Exception (age-scenario {:age :sent :expect {:late true}})) ":late wants :at"))
  (testing "the report checks it and coverage counts the dated ages it names"
    (let [rdef (assoc invoice :scenarios [(age-scenario {:age :sent :row {:state :sent :data {:due_on "2026-09-20"}}
                                                         :expect {:due "2026-10-05"}})])]
      (is (= {:total 1 :checked 1 :deferred [] :violations []} (scenario/report rdef)))
      (is (= [1 2] (scenario/age-coverage rdef))))))

(defn- payment-map []
  {:kind :payment
   :states [:pending :cleared]
   :initial :pending
   :terminal #{:cleared}
   :summary "{data.ref} · {state}"
   :schema [:map
            [:ref [:string {:max 40}]]
            [:cleared_on {:optional true} [:maybe :waymark/date]]]
   :actions {:clear {:from #{:pending} :to :cleared
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "A cleared payment stays cleared."}}}})

(defn- order-map []
  {:kind :order
   :states [:paid :shipped]
   :initial :paid
   :terminal #{:shipped}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:max 100}]]
            [:payment_id {:optional true :kind :payment} [:maybe :waymark/ref]]]
   :expected-age {:paid {:until '(+ (follow (data :payment_id) :cleared_on) (business-days 3))}}
   :actions {:ship {:from #{:paid} :to :shipped
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "Shipped is gone."}}}})

(deftest an-until-age-counts-from-a-date-on-a-linked-row
  (let [order (load-quietly (order-map))
        payments {"p1" {:id "p1" :state :cleared :data {:ref "x" :cleared_on "2026-10-08"}}}
        read (fn [kind id] (when (= :payment kind) (get payments id)))
        cal (with-meta calendar {:read read})
        rows [{:id "linked" :state :paid :updated-at "2026-10-08T15:00:00Z"
               :data {:name "a" :payment_id "p1"}}
              {:id "unlinked" :state :paid :updated-at "2026-10-08T15:00:00Z"
               :data {:name "b"}}]
        aged #(mapv :id (live/aged-rows [order] [] {:order rows} (Instant/parse %1) %2))]
    (testing "the follow is bound to the kind the ref names"
      (is (= '(follow :payment (data :payment_id) :cleared_on)
             (second (:until (:paid (:expected-age order)))))))
    (testing "three business days from Thursday 2026-10-08, past Monday's holiday, end Wednesday"
      (is (= [] (aged "2026-10-15T05:59:00Z" cal)))
      (is (= ["linked"] (aged "2026-10-15T06:01:00Z" cal))))
    (testing "an empty ref is no age, and without a read hook the follow is unknown"
      (is (= ["linked"] (aged "2026-12-01T00:00:00Z" cal)))
      (is (= [] (aged "2026-12-01T00:00:00Z" calendar))))))

(deftest the-gate-takes-an-until-that-follows-a-data-ref
  (is (some? (load-quietly (order-map))))
  (is (some? (load-quietly (payment-map))))
  (doseq [until ['(+ (follow (input :payment_id) :cleared_on) (days 1))
                 '(+ (follow (data :name) :cleared_on) (days 1))]]
    (is (= :expected-age
           (refused-by (assoc-in (order-map) [:expected-age :paid :until] until)))
        (pr-str until))))
