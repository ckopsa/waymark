(ns factory10.org-plan-hold-test
  "The org_plan's go_live as a HOLD (waymark10.holds), end to end over
  the ring handler on a memory engine: the owner files a plan and
  answers its question, the onboarding seat's go_live becomes a held
  call owned by the owner, and the owner's Allow runs it (ticket
  cd22be20). factory10.ci-run-hold-test's arrangement, one kind over.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [waymark10.dev :as dev]
            [waymark10.holds :as holds]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-18T08:00:00Z"))

(defn- world []
  (let [eng (dev/scratch! (main/resources) {:now-fn (constantly t0)})]
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
  "The onboarding seat's sitter: an agent acting for its person."
  {"x-waymark-principal" "onboarding"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(defn- get-row [h plural id]
  (json (req h :get (str "/api/" plural "/" id) {:headers person})))

(defn- log-of [eng id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind :org_plan :resource-id (str id)} {}))))

(defn- filed!
  "A plan the owner filed over the wire. → its id."
  [h]
  (let [made (req h :post "/api/org_plans"
                  {:headers person
                   :body {:name "greenleaf"
                          :brief "A six-person landscaping business."}})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- granted!
  "The agent's ask for the go_live door, approved by its person. → the
  headers it presents."
  [h]
  (let [asked (req h :post "/api/approval_requests"
                   {:headers agent-headers
                    :body {:task "Ask to make the org live."
                           :scope [{:kind "org_plan" :actions ["go_live"]}]}})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked)
                                   "/-/approve")
                      {:headers person})
        _ (assert (= 200 (:status approved)) (pr-str (json approved)))]
    (assoc agent-headers "x-waymark-grant"
           (get-in (json approved) [:data :grant_id]))))

(deftest the-two-walls-are-registered-holds
  (is (holds/hold? :only-a-person-goes-live))
  (is (holds/hold? :only-a-person-answers)))

(deftest the-birth-stamps-the-owner-and-the-directory
  (let [{:keys [h]} (world)
        plan (get-row h "org_plans" (filed! h))]
    (is (= "drafting" (:state plan)))
    (is (= "colton" (get-in plan [:data :owner])))
    (is (= "orgs/greenleaf" (get-in plan [:data :org_dir])))))

(deftest the-owner-answers-the-question-asked
  (let [{:keys [h eng]} (world)
        id (filed! h)]
    (inv/invoke! eng :org_plan id :ask
                 {:key "q1" :finding_key "f1"
                  :question "Who approves a quote over $5k?"}
                 {:principal the-owner})
    (let [done (req h :post (str "/api/org_plans/" id "/-/answer")
                    {:headers person :body {:key "q1" :answer "I do."}})]
      (is (= 200 (:status done)) (pr-str (json done))))
    (let [[q] (get-in (get-row h "org_plans" id) [:data :questions])]
      (is (= "I do." (:answer q)))
      (is (some? (:answered_at q))))
    (testing "a stranger is refused"
      (let [no (req h :post (str "/api/org_plans/" id "/-/answer")
                    {:headers {"x-waymark-principal" "sam"}
                     :body {:key "q1" :answer "Whoever."}})]
        (is (not= 200 (:status no)) (pr-str (json no)))))))

(deftest an-agents-go-live-is-held-for-the-owner-and-the-allow-runs-it
  (let [{:keys [h eng]} (world)
        id (filed! h)
        _ (inv/invoke! eng :org_plan id :propose {} {:principal the-owner})
        _ (inv/invoke! eng :org_plan id :attach_film
                       {:film_url "https://example.com/greenleaf.mp4"}
                       {:principal the-owner})
        as (granted! h)]
    (testing "an org whose branch does not say live does not go live"
      (let [no (req h :post (str "/api/org_plans/" id "/-/go_live")
                    {:headers as :body {}})]
        (is (= 409 (:status no)) (pr-str (json no)))
        (is (= "simulated" (:state (get-row h "org_plans" id))))))
    (walk/flipped! eng id walk/a-head)
    (let [asked (req h :post (str "/api/org_plans/" id "/-/go_live")
                     {:headers as :body {}})
          held (:held_call (json asked))]
      (testing "the agent's go_live is not served: it waits on the owner"
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (true? (:held (json asked))))
        (is (= "simulated" (:state (get-row h "org_plans" id))))
        (let [call (get-row h "held_calls" held)
              why (str (get-in call [:data :why]))]
          (is (= "colton" (get-in call [:data :owner])))
          (is (= {:kind "org_plan" :action "go_live" :id id}
                 (select-keys (get-in call [:data :door]) [:kind :action :id])))
          (testing "its why names the org, the mayor, the budget and the head"
            (is (str/includes? why "Make greenleaf live?") why)
            (is (str/includes? why "Its mayor greenleaf-mayor will sit at $8/week.") why)
            (is (str/includes? why "pull request at 5e469c6") why))))
      (testing "the owner's Allow runs it, as the agent's move"
        (let [allowed (req h :post (str "/api/held_calls/" held "/-/allow")
                           {:headers person})]
          (is (= 200 (:status allowed)) (pr-str (json allowed))))
        (let [plan (get-row h "org_plans" id)]
          (is (= "going_live" (:state plan)))
          (is (= walk/a-head (get-in plan [:data :approved_head]))))
        (let [last-move (last (log-of eng id))]
          (is (= :go_live (:action last-move)))
          (is (= "onboarding" (get-in last-move [:actor :id]))))))))

(deftest only-the-engine-marks-a-going-live-plan-live
  (let [{:keys [h eng]} (world)
        id (walk/going-live! eng (filed! h) the-owner)]
    (is (= "going_live" (:state (get-row h "org_plans" id))))
    (is (thrown? Exception
                 (inv/invoke! eng :org_plan id :mark_live {} {:principal the-owner}))
        "the owner's own hand does not take it")
    (is (= "going_live" (:state (get-row h "org_plans" id))))
    (inv/invoke! eng :org_plan id :mark_live {} walk/the-engine)
    (is (= "live" (:state (get-row h "org_plans" id))))))
