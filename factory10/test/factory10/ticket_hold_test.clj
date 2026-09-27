(ns factory10.ticket-hold-test
  "The ticket's reopen wall as a HOLD (waymark10.holds), end to end
  over the ring handler on a memory engine: an agent's reopen becomes
  a held call owned by its person, the person's Allow runs it, a
  forged `:within` is refused, and a person's own reopen runs
  directly.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.resources.ticket :refer [ticket]]
            [waymark10.dev :as dev]
            [waymark10.holds :as holds]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-09-27T08:00:00Z"))

(defn- world []
  (let [eng (dev/scratch! [ticket] {:now-fn (constantly t0)})]
    {:eng eng :h (dev/handler eng)}))

(defn- req
  ([h method uri] (req h method uri {}))
  ([h method uri {:keys [body headers]}]
   (h (cond-> {:request-method method :uri uri :headers (or headers {})}
        body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private person {"x-waymark-principal" "colton"})

(def ^:private agent-headers
  "The code seat's sitter: an agent acting for its person."
  {"x-waymark-principal" "code-seat"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(def ^:private the-agent
  (assoc (t/principal {:id "code-seat" :type :agent :display "code-seat"})
         :acts-for "colton"))

(defn- get-row [h plural id]
  (json (req h :get (str "/api/" plural "/" id) {:headers person})))

(defn- log-of [eng id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind :ticket :resource-id (str id)} {}))))

(defn- done-ticket!
  "A ticket the person wrote and completed. → its id."
  [h]
  (let [made (req h :post "/api/tickets"
                  {:headers person
                   :body {:title "The code seat walks ticket rows"
                          :detail "Switch the walk from task to ticket."
                          :type "feature"
                          :priority 1
                          :repo "ckopsa/waymark"}})
        _ (assert (= 201 (:status made)) (pr-str (json made)))
        id (id-of made)
        done (req h :post (str "/api/tickets/" id "/-/complete")
                  {:headers person
                   :body {:close_reason "Merged: github:ckopsa/waymark#41."}})]
    (assert (= 200 (:status done)) (pr-str (json done)))
    id))

(defn- granted!
  "The agent's ask for the reopen door, approved by its person. → the
  headers it presents."
  [h]
  (let [asked (req h :post "/api/approval_requests"
                   {:headers agent-headers
                    :body {:task "Reopen a ticket that ended wrongly."
                           :scope [{:kind "ticket" :actions ["reopen"]}]}})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked)
                                   "/-/approve")
                      {:headers person})
        _ (assert (= 200 (:status approved)) (pr-str (json approved)))]
    (assoc agent-headers "x-waymark-grant"
           (get-in (json approved) [:data :grant_id]))))

(deftest the-reopen-wall-is-a-registered-hold
  (is (holds/hold? :only-a-person-reopens)
      "the guard's `:hold true` registered it when the module loaded")
  (is (delegation/hold-guard? "only-a-person-reopens")
      "and the router's one question answers it, as a string too"))

(deftest an-agents-reopen-is-held-for-its-person-and-the-allow-runs-it
  (let [{:keys [h eng]} (world)
        id (done-ticket! h)
        as (granted! h)
        asked (req h :post (str "/api/tickets/" id "/-/reopen") {:headers as})
        held (:held_call (json asked))]
    (testing "the agent's reopen is not served: it waits on the person"
      (is (= 202 (:status asked)) (pr-str (json asked)))
      (is (true? (:held (json asked))))
      (is (= "done" (:state (get-row h "tickets" id))))
      (let [call (get-row h "held_calls" held)]
        (is (= "colton" (get-in call [:data :owner])) "owned by its person")
        (is (= "code-seat" (get-in call [:data :caller])))
        (is (= {:kind "ticket" :action "reopen" :id id}
               (select-keys (get-in call [:data :door]) [:kind :action :id])))))
    (testing "a forged :within, naming a call nobody allowed, is refused"
      (let [e (try (inv/invoke! eng :ticket id :reopen {}
                                {:principal the-agent
                                 :within {:kind :held_call :action :allow
                                          :id held}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "the door did not open")
        (is (= :only-a-person-reopens (some-> (:guard (ex-data e)) name keyword))
            (pr-str (ex-data e))))
      (is (= "done" (:state (get-row h "tickets" id)))))
    (testing "the person's Allow runs the reopen, as the agent's move"
      (let [allowed (req h :post (str "/api/held_calls/" held "/-/allow")
                         {:headers person})]
        (is (= 200 (:status allowed)) (pr-str (json allowed))))
      (is (= "draft" (:state (get-row h "tickets" id))))
      (is (= "done" (:state (get-row h "held_calls" held))))
      (let [last-move (last (log-of eng id))]
        (is (= :reopen (:action last-move)))
        (is (= "code-seat" (get-in last-move [:actor :id])))))
    (testing "and the spent call opens nothing again"
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/invoke! eng :ticket id :reopen {}
                                {:principal the-agent
                                 :within {:kind :held_call :action :allow
                                          :id held}}))))))

(deftest a-persons-own-reopen-runs-directly
  (let [{:keys [h eng]} (world)
        id (done-ticket! h)
        done (req h :post (str "/api/tickets/" id "/-/reopen") {:headers person})]
    (is (= 200 (:status done)) (pr-str (json done)))
    (is (nil? (:held (json done))))
    (is (= "draft" (:state (get-row h "tickets" id))))
    (is (= "colton" (get-in (last (log-of eng id)) [:actor :id])))))

;; ── whom a hold waits on ──────────────────────────────────────────────

(defn- owner-ctx
  "A ctx over a fake store: `:find` answers the rows of a kind whose
  data matches every pair of the where map."
  [principal rows]
  {:principal principal
   :now t0
   :find (fn [kind where _opts]
           (filterv (fn [r]
                      (and (= kind (:kind r))
                           (every? (fn [[k v]]
                                     (= (str v) (str (get-in r [:data k]))))
                                   where)))
                    rows))})

(deftest a-hold-waits-on-the-agents-person
  (let [stray {:id "stray" :type :agent}
        grant {:kind :grant :id "g1" :state :accepted
               :data {:audience "stray"}}
        ask {:kind :approval_request :id "a1" :state :approved
             :data {:grant_id "g1" :approved_by "colton"}}]
    (is (= "colton" (delegation/hold-owner (owner-ctx the-agent [])))
        "a seat's sitter waits on the person it acts for")
    (is (= "colton" (delegation/hold-owner (owner-ctx stray [grant ask])))
        "an agent that sits in no seat waits on the person its grant belongs to")
    (is (nil? (delegation/hold-owner
               (owner-ctx stray [(assoc grant :state :revoked) ask])))
        "a dead grant belongs to nobody here")
    (is (nil? (delegation/hold-owner (owner-ctx stray [])))
        "with no person to ask, there is nobody to hold the call for")
    (is (nil? (delegation/hold-owner
               (owner-ctx {:id "colton" :type :human} [grant ask])))
        "a person's own call is never held")))
