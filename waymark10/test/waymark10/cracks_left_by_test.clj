(ns waymark10.cracks-left-by-test
  "A person's tap and the engine's own actor as `:left-by` sources
  (ticket 3df07d6f): a state either leaves is no no_exit, a state a
  person leaves is still judged by silent_person_exit, and a source
  that is none is still refused. The core kinds left that way declare
  it, and their audit shows no no_exit there."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.mcp-servers :as mcp-servers]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]))

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "History keeps the record."})

(def ^:private ask
  {:kind :left_ask
   :plural "left_asks"
   :states [:open :decided :landed]
   :initial :open
   :terminal #{:landed}
   :summary "{data.title} - {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :actions {:decide {:from #{:open} :to :decided :safety quiet}
             :land {:from #{:decided} :to :landed :safety quiet}}})

(def ^:private left
  [{:in #{:open} :by :person :via :decide :to :decided}
   {:in #{:decided} :by :engine :via :land :to :landed}])

(defn- of [cracks crack state]
  (filterv #(and (= crack (:crack %)) (= state (:state %))) cracks))

(deftest a-person-or-the-engine-leaves-a-state
  (let [cracks (cracks/audit [(r/resource (assoc ask :left-by left))] [])]
    (testing "neither state is no_exit"
      (is (empty? (of cracks "no_exit" "open")))
      (is (empty? (of cracks "no_exit" "decided"))))
    (testing "the state a person leaves still wants the person told"
      (is (seq (of cracks "silent_person_exit" "open")))
      (is (empty? (of cracks "silent_person_exit" "decided")))))
  (testing "without the declaration both are no_exit"
    (let [cracks (cracks/audit [(r/resource ask)] [])]
      (is (seq (of cracks "no_exit" "open")))
      (is (seq (of cracks "no_exit" "decided"))))))

(deftest a-source-that-is-none-is-refused
  (is (thrown-with-msg? Exception #"a mover is one of"
                        (r/resource (assoc ask :left-by
                                           [{:in #{:open} :by :someone
                                             :via :decide :to :decided}])))))

(deftest the-core-kinds-declare-who-leaves-them
  (let [cracks (cracks/audit [held-calls/held-call grants/approval-request
                              mcp-servers/mcp-server seats/model
                              seats/sitting schedules/schedule]
                             [])
        no-exit (fn [kind state]
                  (filterv #(= kind (:kind %)) (of cracks "no_exit" state)))]
    (doseq [[kind states] {"held_call" ["held" "allowed"]
                           "approval_request" ["offered"]
                           "mcp_server" ["live" "dark" "retired"]
                           "model" ["active" "retired"]
                           "sitting" ["open"]
                           "schedule" ["live"]}
            state states]
      (is (empty? (no-exit kind state)) (str kind " " state)))
    (testing "a held call's endings say what they do to the caller"
      (is (empty? (filterv #(and (= "held_call" (:kind %))
                                 (= "dead_wait" (:crack %)))
                           cracks))))))
