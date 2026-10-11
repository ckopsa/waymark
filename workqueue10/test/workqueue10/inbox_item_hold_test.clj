(ns workqueue10.inbox-item-hold-test
  "The inbox item's reopen wall as a HOLD (waymark10.holds), end to end
  over the ring handler on a memory engine: an agent's reopen of a
  dismissal becomes a held call owned by its person, the person's
  Allow runs it, a forged `:within` is refused, a person's own reopen
  runs directly — and the reopen that ran through the held call is
  counted as the PERSON's correction of the seat's `no`.

  No database: dev/scratch! is the whole world.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.inbox-item-hold-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.holds :as holds]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]
            [workqueue10.main :as main])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-09-27T08:00:00Z"))

(defn- world
  "The household's whole registry over memory: `yes` touches `task`,
  and the assembly checks refuse a registry that lacks it."
  []
  (let [eng (dev/scratch! (main/check-resources)
                          {:now-fn (constantly t0)
                           :suppress-mirror-refresh true})]
    {:eng eng :h (dev/handler eng)}))

(defn- req
  ([h method uri] (req h method uri {}))
  ([h method uri {:keys [body headers]}]
   (h (cond-> {:request-method method :uri uri :headers (or headers {})}
        body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp)
                           (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private person {"x-waymark-principal" "colton"})

(def ^:private agent-headers
  "The inbox clerk's sitter: an agent acting for its person."
  {"x-waymark-principal" "inbox-clerk"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(def ^:private the-clerk
  (assoc (t/principal {:id "inbox-clerk" :type :agent :display "inbox-clerk"})
         :acts-for "colton"))

(defn- get-row [h plural id]
  (json (req h :get (str "/api/" plural "/" id) {:headers person})))

(defn- etag-of [h id]
  (get-in (get-row h "inbox_items" id) [:meta :etag]))

(defn- log-of [eng id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind :inbox_item :resource-id (str id)} {}))))

(defn- corrections
  "The clerk's corrections, read as the seats route reads them: the
  framework's own kinds are excluded, so the person's approve of the
  clerk's ask (and its Allow of the held call) is bookkeeping, not a
  correction."
  [eng]
  (let [excluded (into [] (keep (fn [[k rdef]]
                                  (when (= :system (:nav rdef)) (name k))))
                       (inv/resources eng))]
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/corrections-by-model (:storage eng) tx ["inbox-clerk"]
                                    Instant/EPOCH excluded)))))

(defn- move!
  "One door on one row, fenced at the row's current version."
  [h headers id action body]
  (req h :post (str "/api/inbox_items/" id "/-/" action)
       {:headers (assoc headers "if-match" (etag-of h id))
        :body body}))

(defn- granted!
  "The clerk's ask for the tree's doors, approved by its person. → the
  headers it presents."
  [h]
  (let [asked (req h :post "/api/approval_requests"
                   {:headers agent-headers
                    :body {:task "Walk the inbox."
                           :scope [{:kind "inbox_item"
                                    :actions ["research" "no" "reopen"]}]}})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked)
                                   "/-/approve")
                      {:headers person})
        _ (assert (= 200 (:status approved)) (pr-str (json approved)))]
    (assoc agent-headers "x-waymark-grant"
           (get-in (json approved) [:data :grant_id]))))

(defn- dismissed!
  "A message the person queued and researched, and the CLERK said no
  to. → its id."
  [h as]
  (let [made (req h :post "/api/inbox_items"
                  {:headers person
                   :body {:message_id "19b2f0c4d5e6a7b8"
                          :subject "Fwd: invoice for the deck stain"
                          :sender "receipts@hardware.example"
                          :received_at "2026-09-26T11:12:00Z"}})
        _ (assert (= 201 (:status made)) (pr-str (json made)))
        id (id-of made)
        opened (move! h person id "open" {})
        _ (assert (= 200 (:status opened)) (pr-str (json opened)))
        read (move! h person id "research"
                    {:summary "A receipt for a purchase already made."})
        _ (assert (= 200 (:status read)) (pr-str (json read)))
        no (move! h as id "no" {:reason "A receipt, not a request."})]
    (assert (= 200 (:status no)) (pr-str (json no)))
    id))

(deftest the-correction-wall-is-a-registered-hold
  (is (holds/hold? :the-correction-is-a-persons)
      "the guard's `:hold true` registered it when the module loaded"))

(deftest an-agents-reopen-is-held-and-the-allow-counts-as-the-persons-correction
  (let [{:keys [h eng]} (world)
        as (granted! h)
        id (dismissed! h as)
        asked (move! h as id "reopen" {})
        held (:held_call (json asked))]
    (testing "the agent's reopen is not served: it waits on the person"
      (is (= 202 (:status asked)) (pr-str (json asked)))
      (is (true? (:held (json asked))))
      (is (= "dismissed" (:state (get-row h "inbox_items" id))))
      (let [call (get-row h "held_calls" held)]
        (is (= "colton" (get-in call [:data :owner])) "owned by its person")
        (is (= "inbox-clerk" (get-in call [:data :caller])))
        (is (= {:kind "inbox_item" :action "reopen" :id id}
               (select-keys (get-in call [:data :door]) [:kind :action :id])))))
    (testing "a forged :within, naming a call nobody allowed, is refused"
      (let [e (try (inv/invoke! eng :inbox_item id :reopen {}
                                {:principal the-clerk
                                 :if-match (etag-of h id)
                                 :within {:kind :held_call :action :allow
                                          :id held}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "the door did not open")
        (is (= :the-correction-is-a-persons
               (some-> (:guard (ex-data e)) name keyword))
            (pr-str (ex-data e))))
      (is (= "dismissed" (:state (get-row h "inbox_items" id))))
      (is (empty? (corrections eng)) "nothing was corrected yet"))
    (testing "the person's Allow runs the reopen"
      (let [allowed (req h :post (str "/api/held_calls/" held "/-/allow")
                         {:headers person})]
        (is (= 200 (:status allowed)) (pr-str (json allowed))))
      (is (= "researched" (:state (get-row h "inbox_items" id))))
      (is (= "done" (:state (get-row h "held_calls" held)))))
    (testing "and the log says whose yes it was"
      (let [last-move (last (log-of eng id))]
        (is (= :reopen (:action last-move)))
        (is (= "inbox-clerk" (get-in last-move [:actor :id]))
            "the hand is the author's")
        (is (= "colton" (get-in last-move [:actor :allowed_by]))
            "and the yes is the person's")))
    (testing "so the count reads the reopen as the PERSON's correction of the seat's no"
      (is (= [{:model nil :n 1}] (corrections eng))
          "one correction, of the clerk's dismissal, and none of it the seat's own"))))

(deftest a-persons-own-reopen-runs-directly-and-is-a-correction
  (let [{:keys [h eng]} (world)
        as (granted! h)
        id (dismissed! h as)
        done (move! h person id "reopen" {})]
    (is (= 200 (:status done)) (pr-str (json done)))
    (is (nil? (:held (json done))))
    (is (= "researched" (:state (get-row h "inbox_items" id))))
    (is (= "colton" (get-in (last (log-of eng id)) [:actor :id])))
    (is (= [{:model nil :n 1}] (corrections eng)))))
