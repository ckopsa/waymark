(ns waymark10.cross-row-remedy-test
  "A refusal names the fix when the fix is on another row (ticket
  58fe4a33): the four scope dead ends an agent met with remedies [],
  each now handing back the call to make — or saying there is none."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.server.grants :as grants]
            [waymark10.server.problems :as p]
            [waymark10.server.webhooks]
            [waymark10.types :as t]))

(deftest an-extend-ask-on-a-seat-grant-is-handed-the-seat-restate
  (let [scope [{:kind "ticket" :actions ["create"]}]
        inp {:grant_id "g-1" :scope scope}
        ctx {:read (fn [k id]
                     (when (and (= :grant k) (= "g-1" id))
                       {:id "g-1" :data {:seat "seat-9"}}))}
        [v d] (g/evaluate grants/extend-of-a-seat-holds-no-scope nil inp ctx)]
    (is (t/deny? v))
    (testing "the remedy is the seat's restate, on the seat, prefilled"
      (is (= [{:door :seat/restate :id "seat-9"
               :input {:patch true :scope {:add scope}}}]
             (g/resolve-remedies d nil inp (:evidence v)))))))

(deftest a-seat-managed-subscription-names-its-seat
  (let [row {:id "sub-1"
             :data {:managed_by "seat"
                    :receiver {:type "pull" :kind "seat" :id "seat-4"}}}
        guard @#'waymark10.server.webhooks/managed-by-its-seat
        [v d] (g/evaluate guard row {:description "x"}
                          {:principal {:id "ann" :type :person}})]
    (is (t/deny? v))
    (is (= [{:door :seat/restate :id "seat-4"}]
           (g/resolve-remedies d row {} (:evidence v))))))

(deftest a-private-kind-says-there-is-no-remedy
  (let [ctx {:rdef-of (fn [k]
                        (when (= "letter" (name k))
                          {:kind :letter :own-surface {:grantable false}}))}
        [v d] (g/evaluate grants/scope-omits-private-kinds nil
                          {:scope [{:kind "letter"}]} ctx)
        data (ex-data (p/guard-refused :restate :active "refused"
                                       {:guard (:name d)
                                        :remedies (g/remedy-doors d)
                                        :no-remedy (:no-remedy d)}
                                       nil))]
    (is (t/deny? v))
    (is (true? (:no-remedy data)))
    (is (= [] (:remedies data)))))

(deftest an-unknown-kind-is-answered-with-the-nearest-real-ones
  (let [rdefs [{:kind :ticket} {:kind :task} {:kind :seat}
               {:kind :tickler :own-surface {:grantable false}}]
        served #{"ticket" "task" "seat" "tickler"}
        ctx {:action-names (fn [k] (when (served (name k)) ["create"]))
             :rdefs (fn [] rdefs)}
        [v d] (g/evaluate grants/scope-names-real-kinds nil
                          {:scope [{:kind "tickets"}]} ctx)
        near (get-in v [:evidence :nearest])]
    (is (t/deny? v))
    (is (= "ticket" (first near)))
    (is (not-any? #{"tickler"} near) "a private kind is never offered")
    (is (str/includes? (g/render-reason d v nil) "nearest kinds it serves: ticket"))))
