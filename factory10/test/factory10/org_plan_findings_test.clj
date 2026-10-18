(ns factory10.org-plan-findings-test
  "The org_plan's findings as data (ticket 5d62058e, M2b): two audit
  runs over a fixture org land on its plan through record_findings.
  The first records three findings, the second (after a fix) records
  two; the finding that is gone closes and its question is resolved,
  and the keys of the unchanged findings do not move.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-18T08:00:00Z"))

(defn- world []
  (let [eng (dev/scratch! (main/resources) {:now-fn (constantly t0)})]
    {:eng eng :h (dev/handler eng)}))

(defn- req [h method uri {:keys [body headers]}]
  (h (cond-> {:request-method method :uri uri :headers (or headers {})}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private person {"x-waymark-principal" "colton"})

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(def ^:private the-forge
  (t/principal {:id "factory10-source" :type :system
                :display "factory10-source"}))

(defn- plan-of [h id]
  (:data (json (req h :get (str "/api/org_plans/" id) {:headers person}))))

(defn- filed! [h]
  (let [made (req h :post "/api/org_plans"
                  {:headers person
                   :body {:name "greenleaf"
                          :brief "A six-person landscaping business."}})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

;; What `clojure -M:conform --json orgs/greenleaf` answers for the
;; fixture org, before and after the fix: conform's own keys.
(def ^:private quote-approver
  {:key "crack/quote/person_only" :severity "error" :class "crack"
   :subject "quote" :sentence "No one approves a quote over $5k."
   :remedy "Name who approves a quote over $5k."})

(def ^:private late-payer
  {:key "crack/invoice/no_waker" :severity "error" :class "crack"
   :subject "invoice" :sentence "No seat is woken when an invoice is 30 days late."
   :remedy "Name who chases a late invoice."})

(def ^:private unexplained
  {:key "sentence/crew/double-booked" :severity "error" :class "sentence"
   :subject "crew" :sentence "The guard double-booked does not explain itself."
   :remedy "Give the guard an :explain."})

(defn- recorded! [eng id sha findings]
  (inv/invoke! eng :org_plan id :record_findings
               {:head_sha sha :findings findings}
               {:principal the-forge}))

(deftest two-runs-land-on-the-plan
  (let [{:keys [h eng]} (world)
        id (filed! h)]
    (recorded! eng id "aaaaaaa1" [quote-approver late-payer unexplained])
    (inv/invoke! eng :org_plan id :ask
                 {:key "q1" :finding_key (:key quote-approver)
                  :question "Who approves a quote over $5k?"}
                 {:principal the-owner})
    (inv/invoke! eng :org_plan id :ask
                 {:key "q2" :finding_key (:key unexplained)
                  :question "What happens when a crew is double-booked?"}
                 {:principal the-owner})
    (let [first-run (plan-of h id)]
      (testing "the first run records three findings at its head"
        (is (= 3 (count (:findings first-run))))
        (is (= "aaaaaaa1" (:findings_sha first-run)))
        (is (not-any? :resolved_at (:questions first-run))))
      (recorded! eng id "bbbbbbb2" [quote-approver late-payer])
      (let [second-run (plan-of h id)
            by-key (into {} (map (juxt :key identity)) (:questions second-run))]
        (testing "the second run records two, at its own head"
          (is (= 2 (count (:findings second-run))))
          (is (= "bbbbbbb2" (:findings_sha second-run))))
        (testing "the keys of the unchanged findings do not move"
          (is (= (take 2 (map :key (:findings first-run)))
                 (map :key (:findings second-run)))))
        (testing "the finding that is gone closes: its question is resolved"
          (is (some? (:resolved_at (by-key "q2"))))
          (is (nil? (:resolved_at (by-key "q1")))))))))

(def ^:private seat-outside
  {:key "scope/outside-owner/crew-seat/quote/approve" :severity "error"
   :class "scope" :subject "seat crew-seat"
   :sentence "The seat crew-seat holds quote.approve, which its owner does not."
   :remedy "Narrow the seat's scope to its owner's, or widen the owner's first."})

(deftest the-walk-takes-the-findings-in-a-fixed-order
  (let [{:keys [h eng]} (world)
        id (filed! h)
        step #(:key (:next_step (plan-of h id)))
        propose #(:status (req h :post (str "/api/org_plans/" id "/-/propose")
                               {:headers person :body {}}))]
    (recorded! eng id "aaaaaaa1"
               [seat-outside unexplained quote-approver late-payer])
    (testing "three classes: cracks before sentences before seats, ties by key"
      (is (= [(:key late-payer) (:key quote-approver)
              (:key unexplained) (:key seat-outside)]
             (map :key (org-plan/walk-steps (:findings (plan-of h id))))))
      (is (= (:key late-payer) (step))))
    (testing "the step stays until a later run clears its finding"
      (recorded! eng id "aaaaaaa2"
                 [seat-outside unexplained quote-approver late-payer])
      (is (= (:key late-payer) (step)))
      (recorded! eng id "bbbbbbb1" [seat-outside unexplained quote-approver])
      (is (= (:key quote-approver) (step)))
      (recorded! eng id "bbbbbbb2" [seat-outside unexplained])
      (is (= (:key unexplained) (step)))
      (recorded! eng id "bbbbbbb3" [seat-outside])
      (is (= (:key seat-outside) (step))))
    (testing "the walk is not done while a finding is open"
      (is (not= 200 (propose))))
    (testing "the walk ends when the findings are empty, and propose opens"
      (recorded! eng id "ccccccc1" [])
      (is (nil? (:next_step (plan-of h id))))
      (is (= 200 (propose))))))

(deftest only-the-engine-records-findings
  (let [{:keys [h]} (world)
        id (filed! h)
        no (req h :post (str "/api/org_plans/" id "/-/record_findings")
                {:headers person
                 :body {:head_sha "aaaaaaa1" :findings [quote-approver]}})]
    (is (not= 200 (:status no)) (pr-str (json no)))
    (is (empty? (:findings (plan-of h id))))))
