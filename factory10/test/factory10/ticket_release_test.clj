(ns factory10.ticket-release-test
  "A blocked ticket unblocks itself when the last ticket it waits on
  ends, end to end over the ring handler on a memory engine: the
  ending re-judges every ticket that named it, in the same
  transaction, and moves the one with nothing left to wait on back
  where it was blocked from.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.resources.ticket :refer [ticket]]
            [waymark10.dev :as dev]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-09-27T08:00:00Z"))

(defn- world []
  (let [eng (dev/scratch! [ticket] {:now-fn (constantly t0)})]
    {:eng eng :h (dev/handler eng)}))

(def ^:private person {"x-waymark-principal" "colton"})

(defn- req [h method uri body]
  (h (cond-> {:request-method method :uri uri :headers person}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(defn- get-row [h id]
  (json (req h :get (str "/api/tickets/" id) nil)))

(defn- new-ticket!
  "A draft the person wrote. → its id."
  [h title]
  (let [made (req h :post "/api/tickets"
                  {:title title :type "task" :priority 2
                   :repo "ckopsa/waymark"})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- move!
  "The person takes one door on one ticket, naming its version."
  [h id action body]
  (let [etag (get-in (get-row h id) [:meta :etag])
        resp (h {:request-method :post
                 :uri (str "/api/tickets/" id "/-/" (name action))
                 :headers (assoc person "if-match" etag)
                 :body (wire/write-json (or body {}))})]
    (assert (= 200 (:status resp))
            (str (name action) " " (pr-str (json resp))))
    (json resp)))

(defn- last-move [eng id]
  (last (store/with-tx (:storage eng)
          (fn [tx]
            (store/transitions (:storage eng) tx
                               {:kind :ticket :resource-id (str id)} {})))))

(defn- ended! [h id how]
  (move! h id how {:close_reason "Merged: github:ckopsa/waymark#41."}))

(deftest ending-the-last-blocker-opens-a-ticket-blocked-from-open
  (let [{:keys [h eng]} (world)
        blocker (new-ticket! h "The blocker")
        waiter (new-ticket! h "The waiter")]
    (move! h waiter :groom nil)
    (move! h waiter :block {:blocked_by [blocker]})
    (is (= "blocked" (:state (get-row h waiter))))
    (is (= "open" (get-in (get-row h waiter) [:data :blocked_from]))
        "the block keeps where the ticket stood")
    (ended! h blocker :complete)
    (let [row (get-row h waiter)]
      (is (= "open" (:state row)) "back in the queue with no hand on it")
      (is (= [] (get-in row [:data :blocked_by])))
      (is (nil? (get-in row [:data :blocked_from]))))
    (testing "by the unblock transition, the one a seat's wake_on names"
      (let [m (last-move eng waiter)]
        (is (= :unblock (:action m)))
        (is (= "blocked" (name (:from-state m))))
        (is (= "open" (name (:to-state m))))))))

(deftest ending-one-of-two-blockers-leaves-the-other
  (let [{:keys [h]} (world)
        first-blocker (new-ticket! h "The first blocker")
        second-blocker (new-ticket! h "The second blocker")
        waiter (new-ticket! h "The waiter")]
    (move! h waiter :groom nil)
    (move! h waiter :block {:blocked_by [first-blocker second-blocker]})
    (ended! h first-blocker :complete)
    (let [row (get-row h waiter)]
      (is (= "blocked" (:state row)) "one blocker still stands")
      (is (= [second-blocker] (get-in row [:data :blocked_by]))
          "the ended one leaves the list")
      (is (= "open" (get-in row [:data :blocked_from]))
          "the restatement keeps where it was blocked from"))
    (ended! h second-blocker :complete)
    (is (= "open" (:state (get-row h waiter))) "and the last one opens it")))

(deftest a-ticket-blocked-from-draft-returns-to-draft
  (let [{:keys [h eng]} (world)
        blocker (new-ticket! h "The blocker")
        waiter (new-ticket! h "The waiter")]
    (move! h waiter :block {:blocked_by [blocker]})
    (is (= "draft" (get-in (get-row h waiter) [:data :blocked_from])))
    (ended! h blocker :complete)
    (let [row (get-row h waiter)]
      (is (= "draft" (:state row))
          "a draft nobody groomed does not reach the queue by its blocker ending")
      (is (= [] (get-in row [:data :blocked_by]))))
    (is (= :return_to_draft (:action (last-move eng waiter))))))

(deftest a-draft-blocked-with-then-open-reaches-the-queue
  (let [{:keys [h eng]} (world)
        blocker (new-ticket! h "The blocker")
        waiter (new-ticket! h "The waiter")]
    (move! h waiter :block {:blocked_by [blocker] :then "open"})
    (is (= "blocked" (:state (get-row h waiter))))
    (is (= "open" (get-in (get-row h waiter) [:data :blocked_from]))
        "the block says where the draft goes")
    (ended! h blocker :complete)
    (let [row (get-row h waiter)]
      (is (= "open" (:state row)) "groomed in waiting, so the ending queues it")
      (is (= [] (get-in row [:data :blocked_by])))
      (is (nil? (get-in row [:data :blocked_from]))))
    (testing "by the unblock transition, the one a seat's wake_on names"
      (is (= :unblock (:action (last-move eng waiter)))))))

(deftest a-draft-blocked-with-then-draft-returns-to-draft
  (let [{:keys [h eng]} (world)
        blocker (new-ticket! h "The blocker")
        waiter (new-ticket! h "The waiter")]
    (move! h waiter :block {:blocked_by [blocker] :then "draft"})
    (is (= "draft" (get-in (get-row h waiter) [:data :blocked_from])))
    (ended! h blocker :complete)
    (is (= "draft" (:state (get-row h waiter))))
    (is (= :return_to_draft (:action (last-move eng waiter))))))

(deftest a-restated-block-can-say-then-open-for-a-blocked-draft
  (let [{:keys [h]} (world)
        blocker (new-ticket! h "The blocker")
        waiter (new-ticket! h "The waiter")]
    (move! h waiter :block {:blocked_by [blocker]})
    (move! h waiter :block {:blocked_by [blocker] :then "open"})
    (is (= "open" (get-in (get-row h waiter) [:data :blocked_from])))
    (ended! h blocker :complete)
    (is (= "open" (:state (get-row h waiter))))))

(deftest a-ticket-blocked-from-the-queue-returns-to-it-whatever-then-says
  (let [{:keys [h]} (world)
        blocker (new-ticket! h "The blocker")
        waiter (new-ticket! h "The waiter")]
    (move! h waiter :groom nil)
    (move! h waiter :block {:blocked_by [blocker] :then "draft"})
    (is (= "open" (get-in (get-row h waiter) [:data :blocked_from])))
    (ended! h blocker :complete)
    (is (= "open" (:state (get-row h waiter))))))

(deftest dropping-a-blocker-counts-as-ending-it
  (let [{:keys [h]} (world)
        blocker (new-ticket! h "The blocker")
        waiter (new-ticket! h "The waiter")]
    (move! h waiter :groom nil)
    (move! h waiter :block {:blocked_by [blocker]})
    (ended! h blocker :drop)
    (is (= "open" (:state (get-row h waiter))))))

;; ticket 775b6427: a drop of the ticket another domain was asked for
(deftest a-declined-ask-sends-the-waiting-ticket-to-draft
  (let [{:keys [h eng]} (world)
        asker (new-ticket! h "The kitchen says when to leave")
        made (req h :post "/api/tickets"
                  {:title "Run the music server" :type "task" :priority 2
                   :repo "ckopsa/waymark" :needed_by asker})
        asked (id-of made)]
    (is (= 201 (:status made)) (pr-str (json made)))
    (move! h asker :block {:blocked_by [asked] :then "open"})
    (ended! h asked :drop)
    (let [row (get-row h asker)]
      (is (= "draft" (:state row)) "not the queue: its mayor plans again")
      (is (= "declined by factory: Merged: github:ckopsa/waymark#41."
             (get-in row [:data :shelved_because])))
      (is (= [] (get-in row [:data :blocked_by]))))
    (is (= :turn_back (:action (last-move eng asker))))))
