(ns waymark10.sitting-at-work-test
  "A sitting's page shows the seat at work (ticket ff5bfbb9): its name,
  Working or Done, and each call in its owner's words, newest last."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.seats :as seats]
            [waymark10.server.transcripts :as transcripts]))

(defn- rdef-of [token]
  (get {:prospect {:kind :prospect :plural "prospects"}} (keyword (name token))))

(def calls
  ;; out of order on purpose: the page sorts by the call's place
  [{:n 3 :tool "waymark_invoke" :row_kind "prospect" :row_id "p2"
    :row_summary "" :action "decline" :outcome "held" :count 0
    :why "Cedar does no research work."}
   {:n 1 :tool "waymark_get" :row_kind "prospect" :row_id "p2"
    :row_summary "Cedar Bakery · New" :action "" :outcome "read" :count 0}
   {:n 2 :tool "waymark_invoke" :row_kind "prospect" :row_id "p1"
    :row_summary "Acme Robotics · Qualified" :action "qualify" :outcome "moved"
    :count 0 :why "Acme builds research robots."}])

(deftest a-sitting-reads-as-its-calls-in-order
  (let [w (seats/at-work rdef-of "dress_rehearsal-mayor" {:state :open} calls)]
    (testing "it leads with the seat's name and Working while open"
      (is (= "Dress rehearsal mayor · Working" (:lead w)))
      (is (true? (:working w))))
    (testing "a read, a move with its why and a held call: three lines in order"
      (is (= ["Read Cedar Bakery"
              "Qualified Acme Robotics: Acme builds research robots."
              "Asked the principal to decline Cedar Bakery: Cedar does no research work."]
             (mapv :text (:calls w)))))
    (testing "each line links the row it named"
      (is (= "/api/prospects/p2" (:href (first (:calls w))))))))

(def ^:private chrome
  "What the film's chrome-leaks counts on a page: an /api path, a uuid
  or a raw key (snake_case)."
  #"/api/|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}|\b[a-z0-9]+_[a-z0-9_]+\b")

(deftest a-sitting-page-shows-no-chrome
  (let [id "0b9c1d2e-3f40-4a5b-8c6d-7e8f9a0b1c2d"
        w (seats/at-work rdef-of "dress_rehearsal-mayor" {:state :open}
                         (conj calls
                               {:n 4 :tool "waymark_query" :row_kind "sitting_call" :outcome "read" :count 2}
                               {:n 5 :tool "bench__edit_many" :outcome ""}
                               {:n 6 :tool "waymark_invoke" :outcome "refused"}
                               {:n 7 :tool "waymark_invoke" :row_kind "prospect" :row_id id
                                :action "mark_paid" :outcome "moved" :why "Paid in full."}))]
    (doseq [line (cons (:lead w) (map :text (:calls w)))]
      (is (nil? (re-find chrome line)) line))))

(deftest a-closed-sitting-is-done
  (is (= "The seat · Done" (:lead (seats/at-work nil nil {:state "closed"} [])))))

(deftest a-seat-links-its-open-sitting
  (let [find' (fn [kind where _]
                (when (and (= :sitting kind) (= "open" (:state where)))
                  [{:id "st-1"}]))]
    (is (= "/api/sittings/st-1"
           (get-in (seats/reads-as rdef-of find' {:id "s1" :data {}})
                   [:sitting :href])))))

(deftest the-record-keeps-the-callers-why
  (is (= "Acme builds research robots."
         (:why (transcripts/call-entry "waymark_invoke"
                                       {:kind "prospect" :id "p1" :action "qualify"
                                        :why "Acme builds research robots."}
                                       {:summary "Acme Robotics · Qualified"} false)))))
