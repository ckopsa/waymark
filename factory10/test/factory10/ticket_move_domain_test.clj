(ns factory10.ticket-move-domain-test
  "A ticket moves into another domain with every ticket under it and
  their open changes, end to end over the ring handler on a memory
  engine (ticket 66d080b0): the move walks each child's own door and
  each open change's, in the same transaction.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [factory10.resources.ticket :refer [ticket]]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-04T08:00:00Z"))

(defn- world []
  (let [eng (dev/scratch! (vec (main/resources)) {:now-fn (constantly t0)})]
    {:eng eng :h (dev/handler eng)}))

(def ^:private person {"x-waymark-principal" "colton"})

(defn- req [h headers method uri body]
  (h (cond-> {:request-method method :uri uri :headers headers}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(defn- get-row [h plural id]
  (json (req h person :get (str "/api/" plural "/" id) nil)))

(defn- domain-of [h plural id]
  (get-in (get-row h plural id) [:data :domain]))

(defn- new-domain!
  "The person makes a domain. → its id."
  [h nm]
  (let [made (req h person :post "/api/domains"
                  {:name nm
                   :charter "Keep the house running."
                   :budget_usd_per_week 10})]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- new-ticket!
  "A draft the person wrote. → its id."
  [h title extra]
  (let [made (req h person :post "/api/tickets"
                  (merge {:title title :type "task" :priority 2
                          :repo "ckopsa/waymark"}
                         extra))]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- move!
  "One door on one ticket by the hand `headers` name, naming its
  version. → the response."
  [h headers id action body]
  (let [etag (get-in (get-row h "tickets" id) [:meta :etag])]
    (req h (assoc headers "if-match" etag) :post
         (str "/api/tickets/" id "/-/" (name action)) (or body {}))))

(defn- moved!
  "The person takes one door, and it opens."
  [h id action body]
  (let [resp (move! h person id action body)]
    (assert (= 200 (:status resp))
            (str (name action) " " (pr-str (json resp))))
    (json resp)))

(defn- open-change!
  "The change a sit mints beside a ticket, before any pull request. →
  its id."
  [eng ticket-id]
  (str (:id (:row (inv/create! eng :change
                               {:change_id (str "ticket:" ticket-id)
                                :born_from (str "ticket:" ticket-id)
                                :repository "ckopsa/waymark"
                                :title "The change beside the ticket"
                                :head_branch (str "bench/" ticket-id)}
                               {:principal mirror/source-principal})))))

(deftest a-move-takes-the-children-and-the-open-change
  (let [{:keys [h eng]} (world)
        _ (new-domain! h "household")
        parent (new-ticket! h "The meal planner" {})
        first-child (new-ticket! h "Its first piece" {:parent parent})
        second-child (new-ticket! h "Its second piece" {:parent parent})
        change (open-change! eng first-child)]
    ;; a deferred child takes the hidden door, a draft the hand's own
    (moved! h second-child :groom nil)
    (moved! h second-child :defer {:defer_until "2026-11-19"})
    (moved! h parent :move_domain_draft {:domain "household"})
    (is (= "household" (domain-of h "tickets" parent)))
    (is (= "household" (domain-of h "tickets" first-child)))
    (is (= "household" (domain-of h "tickets" second-child))
        "a deferred child goes with its parent")
    (is (= "household" (domain-of h "changes" change))
        "and so does the open change born from a child")
    (is (= "deferred" (:state (get-row h "tickets" second-child)))
        "the move writes the domain and moves no state")))

(deftest a-move-names-a-domain-that-stands
  (let [{:keys [h]} (world)
        id (new-ticket! h "The meal planner" {})
        refused (move! h person id :move_domain_draft {:domain "attic"})]
    (is (not= 200 (:status refused)))
    (is (str/includes? (pr-str (json refused)) "no active domain is named attic"))
    (is (not= "attic" (domain-of h "tickets" id)))))

(deftest a-hand-that-is-no-mayor-and-no-person-does-not-move-it
  ;; judged on the envelope, as ticket_test judges a door: an agent with
  ;; no grant meets a 404 at the wire before any guard is asked
  (let [row {:kind :ticket :id "01HZQ7Y7F2R3W4V5X6Y7Z8A9B0" :state :open
             :data {:title "The meal planner" :type "task" :priority 2
                    :repo "ckopsa/waymark"}}
        door (fn [principal action]
               (render/action-availability
                ticket action row {:principal principal :now t0 :mode :probe}))
        a-model-alone {:id "some-model" :type :agent :roles #{}}
        the-person {:id "colton" :type :person :roles #{}}]
    (testing "a model that sits in no mayor's seat and acts for no person"
      (let [shut (door a-model-alone :move_domain)]
        (is (= :unavailable (:status shut)))
        (is (= :the-domains-mayor-or-a-person-moves-it (:name (:denier shut))))
        (is (str/includes? (str (:reason shut)) "mayor")
            "the sentence names who may make the move")))
    (testing "a person makes it"
      (is (= :available (:status (door the-person :move_domain)))))
    (testing "and the door a child is carried through is no hand's"
      (doseq [principal [a-model-alone the-person]]
        (is (= :hidden
               (:status (render/action-availability
                         ticket :move_domain_deferred (assoc row :state :deferred)
                         {:principal principal :now t0 :mode :probe}))))))))
