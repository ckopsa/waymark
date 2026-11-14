(ns factory10.plan-part-test
  "The plan_part: an org_plan's tree of parts, end to end over the ring
  handler on a memory engine (ticket 7fc1415a). The owner lays out a
  goal, two branches and four leaves, agrees them in one bulk call,
  restates one and drops a branch with its children; an agent's agree
  is held for the owner's tap; a plan that is not drafting, and a
  parent from another plan, are refused.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [waymark10.dev :as dev]
            [waymark10.holds :as holds]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-11-14T08:00:00Z"))

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

(defn- state-of [h id] (:state (get-row h "plan_parts" id)))

(defn- filed!
  "A plan the owner filed over the wire. → its id."
  [h org]
  (let [made (req h :post "/api/org_plans"
                  {:headers person
                   :body {:name org
                          :brief "An R&D tax firm. We run each client's study and file the credit."}})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- part
  "A part posted by `headers` on `plan`. → the response."
  [h headers plan body]
  (req h :post "/api/plan_parts" {:headers headers :body (assoc body :plan plan)}))

(defn- part!
  "A part the owner made on `plan`. → its id."
  [h plan body]
  (let [made (part h person plan body)]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- granted!
  "The agent's ask for the agree door, approved by its person. → the
  headers it presents."
  [h]
  (let [asked (req h :post "/api/approval_requests"
                   {:headers agent-headers
                    :body {:task "Ask to agree the plan's parts."
                           :scope [{:kind "plan_part" :actions ["agree"]}]}})
        _ (assert (= 201 (:status asked)) (pr-str (json asked)))
        approved (req h :post (str "/api/approval_requests/" (id-of asked)
                                   "/-/approve")
                      {:headers person})
        _ (assert (= 200 (:status approved)) (pr-str (json approved)))]
    (assoc agent-headers "x-waymark-grant"
           (get-in (json approved) [:data :grant_id]))))

(deftest the-two-walls-are-registered-holds
  (is (holds/hold? :only-the-owner-agrees))
  (is (holds/hold? :only-the-owner-drops)))

(deftest the-owner-builds-agrees-restates-and-drops-a-tree
  (let [{:keys [h]} (world)
        plan (filed! h "rd_tax")
        goal (part! h plan {:title "An R&D tax firm"})
        studies (part! h plan {:title "Studies" :parent goal :order 1
                               :home "new_kind" :home_name "study"})
        filing (part! h plan {:title "Filing" :parent goal :order 2})
        leaves [(part! h plan {:title "Interviews" :parent studies})
                (part! h plan {:title "Evidence" :parent studies
                               :home "built_in" :home_name "attachment"})
                (part! h plan {:title "Form 6765" :parent filing})
                (part! h plan {:title "Amended returns" :parent filing})]
        all (into [goal studies filing] leaves)]
    (testing "a person's part is born proposed, stated, with a home"
      (let [g (get-row h "plan_parts" goal)]
        (is (= "proposed" (:state g)))
        (is (= "stated" (get-in g [:data :source])))
        (is (= "undecided" (get-in g [:data :home])))))
    (testing "the owner agrees all seven in one bulk call"
      (let [done (req h :post "/api/plan_parts/-/agree_many"
                      {:headers person :body {:ids all}})]
        (is (= 200 (:status done)) (pr-str (json done)))
        (is (= 7 (get-in (json done) [:data :succeeded])) (pr-str (json done))))
      (is (every? #(= "agreed" (state-of h %)) all)))
    (testing "restating an agreed part sends it back to proposed"
      (let [;; restate is an edit door, so it is fenced on the version read
            etag (get-in (get-row h "plan_parts" (first leaves)) [:meta :etag])
            done (req h :post (str "/api/plan_parts/" (first leaves) "/-/restate")
                      {:headers (assoc person "if-match" etag)
                       :body {:title "Client interviews"}})]
        (is (= 200 (:status done)) (pr-str (json done))))
      (let [p (get-row h "plan_parts" (first leaves))]
        (is (= "proposed" (:state p)))
        (is (= "Client interviews" (get-in p [:data :title])))
        (is (= studies (get-in p [:data :parent])))))
    (testing "dropping a branch drops its children with it"
      (let [done (req h :post (str "/api/plan_parts/" studies "/-/drop")
                      {:headers person :body {:reason "The client's accountant runs the studies."}})]
        (is (= 200 (:status done)) (pr-str (json done))))
      (is (= "dropped" (state-of h studies)))
      (is (= ["dropped" "dropped"] (mapv #(state-of h %) (take 2 leaves))))
      (is (= ["agreed" "agreed" "agreed" "agreed"]
             (mapv #(state-of h %) (into [goal filing] (drop 2 leaves))))
          "the other branch stands"))
    (testing "the plan counts its parts and draws the tree"
      (let [p (get-row h "org_plans" plan)
            tree (str (get-in p [:data :parts_tree]))]
        (is (= {:proposed 0 :agreed 4 :dropped 3 :total 7}
               (get-in p [:data :parts])))
        (is (str/includes? tree "- **An R&D tax firm**") tree)
        (is (str/includes? tree "  - **Studies** · new_kind `study` · dropped") tree)
        (is (str/includes? tree "    - **Form 6765**") tree)))))

(deftest an-agents-agree-is-held-for-the-owner-and-the-allow-runs-it
  (let [{:keys [h]} (world)
        plan (filed! h "rd_tax")
        goal (part! h plan {:title "An R&D tax firm"})
        as (granted! h)
        asked (req h :post (str "/api/plan_parts/" goal "/-/agree")
                   {:headers as :body {}})
        held (:held_call (json asked))]
    (testing "the agent's agree is not served: it waits on the owner"
      (is (= 202 (:status asked)) (pr-str (json asked)))
      (is (true? (:held (json asked))))
      (is (= "proposed" (state-of h goal)))
      (is (= "colton" (get-in (get-row h "held_calls" held) [:data :owner]))))
    (testing "the owner's Allow moves the part"
      (let [allowed (req h :post (str "/api/held_calls/" held "/-/allow")
                         {:headers person})]
        (is (= 200 (:status allowed)) (pr-str (json allowed))))
      (is (= "agreed" (state-of h goal))))))

(deftest a-part-is-refused-off-a-drafting-plan-and-under-another-plan
  (let [{:keys [h eng]} (world)
        plan (filed! h "rd_tax")
        other (filed! h "landscaping")
        theirs (part! h other {:title "A landscaping business"})]
    (testing "a parent from another plan is refused"
      (let [no (part h person plan {:title "Mowing" :parent theirs})]
        (is (not= 201 (:status no)) (pr-str (json no)))
        (is (str/includes? (pr-str (json no)) "the-parent-is-of-this-plan")
            (pr-str (json no)))))
    (testing "a plan that is not drafting takes no part"
      (walk/proposed! eng plan the-owner)
      (is (not= "drafting" (:state (get-row h "org_plans" plan))))
      (let [no (part h person plan {:title "An R&D tax firm"})]
        (is (not= 201 (:status no)) (pr-str (json no)))
        (is (str/includes? (pr-str (json no)) "the-plan-is-drafting")
            (pr-str (json no)))))))
