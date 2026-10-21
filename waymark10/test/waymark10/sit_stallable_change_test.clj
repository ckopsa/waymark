(ns waymark10.sit-stallable-change-test
  "A sit hands a seat only a change it can stall (ticket a60aed8e): a
  merged or closed change is not the seat's, a stuck one is context and
  never preferred over a live one, and a live one is handed as before."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.mcp]))

(def ^:private minted-change #'waymark10.server.mcp/minted-change)
(def ^:private handed-change #'waymark10.server.mcp/handed-change)

(def ^:private stallable #{:open :submitted :failing})

(def ^:private walk {"kind" "ticket" "rows" [{"id" "t1"}]})

(defn- change [id state data] {:id id :state state :data data})

(defn- over-rows
  "The lookups `minted-change` makes, answered from `rows` alone."
  [rows]
  {#'waymark10.server.mcp/change-in-state
   (fn [_ where states]
     (let [hit (filter (fn [r] (every? (fn [[k v]] (= v (get-in r [:data k])))
                                       where))
                       rows)]
       (some (fn [s] (some #(when (= s (:state %)) %) hit)) states)))
   #'waymark10.server.mcp/change-by-id
   (fn [_ id] (first (filter #(= id (get-in % [:data :change_id])) rows)))
   #'waymark10.server.mcp/walk-home
   (fn [_ _] [nil "no repository"])})

(defn- minted [rows]
  (with-redefs-fn (over-rows rows) #(minted-change nil nil walk)))

(deftest a-merged-change-is-not-the-seats
  (testing "the lookup does not find a merged change by its change_id"
    (is (nil? (first (minted [(change "m" :merged {:change_id "ticket:t1"
                                                   :born_from "ticket:t1"})])))))
  (testing "the sit hands no change for a merged or closed one"
    (doseq [state [:merged :closed]]
      (let [[c note] (handed-change stallable (change "x" state {}))]
        (is (nil? c))
        (is (string? note))))))

(deftest a-stuck-change-is-not-preferred-over-a-live-one
  (let [live (change "live" :open {:change_id "github:o/r#2"
                                   :born_from "ticket:t1"})
        stuck (change "stuck" :stuck {:change_id "ticket:t1"
                                      :born_from "ticket:t1"})]
    (is (= "live" (:id (first (minted [stuck live])))))
    (testing "a stuck one alone is handed as context, with its note"
      (let [[c note] (handed-change stallable stuck)]
        (is (= "stuck" (:id c)))
        (is (string? note))))))

(deftest a-live-change-is-handed-as-before
  (doseq [state [:open :submitted :failing]]
    (let [live (change "live" state {:change_id "ticket:t1"
                                     :born_from "ticket:t1"})]
      (is (= "live" (:id (first (minted [live])))))
      (is (= [live nil] (handed-change stallable live))))))
