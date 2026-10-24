(ns waymark10.cracks-leases-test
  "Leases build ticket 7 (docs/spec-leases.md §5): the cracks audit
  reports held leases past their own end, and held leases never let go
  whose holder has ended. A clean house reports neither."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.cracks :as cracks]))

(def ^:private now "2026-10-24T12:00:00Z")

(defn- lease
  [id data & {:keys [ended state] :or {state :held}}]
  {:id id :state state
   :data (merge {:subject_kind "ticket" :subject_id (str "t-" id)
                 :holder_kind "sitting" :holder_id (str "s-" id)}
                data)
   :holder_ended (boolean ended)})

(defn- of [leases crack]
  (filterv #(= crack (:crack %)) (cracks/audit [] [] {:leases leases :now now})))

(deftest a-past-due-lease-is-reported
  (let [[c & more] (of [(lease "a" {:expires_at "2026-10-24T11:00:00Z"})
                        (lease "b" {:expires_at "2026-10-24T13:00:00Z"})]
                       "lease_past_due")]
    (is (nil? more))
    (is (= "lease" (:kind c)))
    (is (nil? (:state c)))
    (is (= 1 (:rows c)))
    (is (= ["a"] (:leases c)))
    (is (seq (:what_would_close_it c)))))

(deftest a-lease-on-an-ended-holder-is-reported
  (let [[c & more] (of [(lease "a" {} :ended true)
                        (lease "b" {})]
                       "lease_holder_ended")]
    (is (nil? more))
    (is (= "lease" (:kind c)))
    (is (nil? (:state c)))
    (is (= 1 (:rows c)))
    (is (= ["a"] (:leases c)))
    (is (seq (:what_would_close_it c))))
  (testing "a released lease outlives its holder on its grace"
    (is (empty? (of [(lease "a" {:released_at "2026-10-24T11:59:00Z"
                                  :expires_at "2026-10-24T12:05:00Z"}
                            :ended true)]
                    "lease_holder_ended")))))

(deftest a-clean-house-reports-neither
  (let [clean [(lease "a" {})
               (lease "b" {:expires_at "2026-10-24T13:00:00Z"})
               (lease "c" {:expires_at "2026-10-24T11:00:00Z"
                           :ended_at "2026-10-24T11:00:00Z"}
                      :state :ended :ended true)]]
    (is (empty? (of clean "lease_past_due")))
    (is (empty? (of clean "lease_holder_ended"))))
  (testing "no leases read is no finding"
    (is (empty? (filterv #(= "lease" (:kind %)) (cracks/audit [] []))))))
