(ns waymark10.follow-test
  "The follow op (docs/spec-related-row-law.md §2, build step 2): one
  hop to a field of the row a ref names, the target kind written into
  the tree where the schema says it, :reads derived from that tree, one
  read per (kind, id) per verdict. Over a fixture pair of kinds — a meal
  and the day that plans it: allow, deny, and unknown when no :read
  hook is lent."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.checks :as checks]
            [waymark10.checks-assembly :as ca]
            [waymark10.declare :refer [defguard refuse]]
            [waymark10.expr :as e]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.decision]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────────────────

(defguard meal-is-listed
  (refuse "That meal is not on the family meal list yet. Accept a suggestion first.")
  '(= (follow (input :meal_id) :state) "on_list")
  {:remedies [{:door :fw_meal/accept :id '(input :meal_id)}]
   :errors {:meal_id "not an on-list meal"}})

(defguard planned-meal-is-named
  (refuse "The day's meal has a name before it is served.")
  '(and (is-set (follow (data :meal_id) :id))
        (is-set (follow (data :meal_id) :name)))
  {:remedies [:fw_day/plan]})

(defn- meal-map []
  {:kind :fw_meal
   :plural "fw_meals"
   :states [:suggested :on_list]
   :initial :suggested
   :terminal #{:on_list}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 40}]]]
   :actions {:accept {:from #{:suggested} :to :on_list
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "Accepted is on the list."}}}})

(defn- day-map [guards]
  {:kind :fw_day
   :plural "fw_days"
   :states [:open :planned :served]
   :initial :open
   :terminal #{:served}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 40}]]
            [:meal_id {:optional true :kind :fw_meal} [:maybe :waymark/ref]]]
   :actions {:plan {:from #{:open} :to :planned
                    :input [:map
                            [:meal_id {:kind :fw_meal} :waymark/ref]
                            [:note {:optional true} [:maybe [:string {:max 40}]]]]
                    :guards (vec (:plan guards))
                    :safety {:idempotent true :reversible false
                             :confirm false
                             :one-way "Planned is planned."}}
             :serve {:from #{:planned} :to :served
                     :guards (vec (:serve guards))
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Served is history."}}}})

(def ^:private world
  (delay {:meal (r/resource (meal-map))
          :day (r/resource (day-map {:plan [meal-is-listed]
                                     :serve [planned-meal-is-named]}))}))

(defn- guard-on [door]
  (first (get-in (:day @world) [:actions door :guards])))

(defn- judge [door row inp ctx]
  (first (g/evaluate (guard-on door) row inp ctx)))

(defn- meals
  "A :read hook over id → meal row, and the count of reads it served."
  [rows]
  (let [n (atom 0)]
    [n (fn [kind id]
         (swap! n inc)
         (when (= :fw_meal kind) (get rows (str id))))]))

;; ── 1. normalization: the kind is written in, :reads derived ─────────

(deftest the-kind-is-written-into-the-tree
  (let [g (guard-on :plan)]
    (is (= (e/normalize '(= (follow :fw_meal (input :meal_id) :state) "on_list"))
           (:when g)))
    (is (= [:fw_meal] (:reads g)) ":reads is derived from the follow")
    (is (= [:meal_id] (:judges g)))
    (is (= [:fw_meal] (:reads (guard-on :serve)))
        "a (data …) ref binds from the row's own schema")
    (testing "the def'd guard, before a kind carries it, has no kind to read"
      (is (= [] (:reads meal-is-listed))))
    (testing "the bound tree crosses the wire and comes back the same law"
      (is (= (:when g) (wire/wire->form (wire/form->wire (:when g))))))))

;; ── 2. evaluation: allow, deny, unknown ─────────────────────────────

(deftest follow-allows-denies-and-is-unknown-without-a-hook
  (let [[_ read] (meals {"m1" {:id "m1" :state :on_list :data {:name "Tacos"}}
                         "m2" {:id "m2" :state :suggested :data {:name "Soup"}}})]
    (testing "allow: the meal it names is on the list"
      (is (t/allow? (judge :plan {:data {}} {:meal_id "m1"} {:read read}))))
    (testing "deny: a suggested meal, with the per-field error"
      (let [v (judge :plan {:data {}} {:meal_id "m2"} {:read read})]
        (is (t/deny? v))
        (is (= {:meal_id ["not an on-list meal"]} (:errors v)))))
    (testing "deny: an id no row has reads nil — not an on-list meal"
      (is (t/deny? (judge :plan {:data {}} {:meal_id "m9"} {:read read}))))
    (testing "unknown: no :read hook is pending, never a refusal"
      (let [v (judge :plan {:data {}} {:meal_id "m2"} {})]
        (is (t/allow? v))
        (is (t/pending-input? v))))))

(deftest one-read-per-row-per-verdict
  (let [[n read] (meals {"m1" {:id "m1" :state :on_list :data {:name "Tacos"}}})]
    (is (t/allow? (judge :serve {:data {:title "Mon" :meal_id "m1"}} nil {:read read})))
    (is (= 1 @n) "two follows of one ref cost one read")))

(deftest an-unknown-is-named-where-it-is-shown
  (testing ":vars in a deny: an unknown var reads unknown, not the object"
    (let [gd (g/expr {:name :noted :explain "{who} left no note."
                      :remedies [:fw_day/plan]
                      :when '(= (input :note) "x")
                      :vars {:who '(me)}})
          [v _] (g/evaluate gd {:data {}} {:note "y"} {})]
      (is (t/deny? v))
      (is (= {:who "unknown"} (:vars v)))))
  (testing "the decision record writes the word unknown"
    (is (= "unknown" (#'waymark10.server.decision/recordable e/unknown)))
    (is (= {"who" "unknown"}
           (#'waymark10.server.decision/recordable {:who e/unknown}))))
  (testing "a require gate at create: an unknown spec is pending, not truthy"
    (let [gd {:name :needs-me :require :fw_day
              :require/spec {:expr (e/normalize '(me))}}
          [v _] (g/evaluate gd nil {:title "Mon"} {})]
      (is (t/allow? v))
      (is (t/pending-input? v)))))

;; ── 3. the gate ─────────────────────────────────────────────────────────────────

(deftest the-gate-names-what-follow-cannot-do
  (testing "[follow-not-a-ref]: a field with no :kind"
    (let [bad (g/expr {:name :note-follows :explain "The note names a row."
                       :remedies [:fw_day/plan]
                       :when '(is-set (follow (input :note) :state))})]
      (is (thrown-with-msg? Exception #"\[follow-not-a-ref\]"
                            (r/resource (day-map {:plan [bad]}))))))
  (testing "[reads-handwritten]: a :when guard derives its reads"
    (let [bad (g/expr {:name :handwritten :explain "The meal is listed."
                       :remedies [:fw_day/plan]
                       :reads [:fw_meal]
                       :when '(= (follow (input :meal_id) :state) "on_list")})]
      (is (thrown-with-msg? Exception #"\[reads-handwritten\]"
                            (r/resource (day-map {:plan [bad]}))))))
  (testing "[follow-unknown-field]: the target kind has no such field"
    (let [bad (g/expr {:name :no-such :explain "The meal has a colour."
                       :remedies [:fw_day/plan]
                       :when '(is-set (follow (input :meal_id) :colour))})]
      (is (thrown-with-msg? Exception #"\[follow-unknown-field\]"
                            (ca/run-all {:kinds {:fw_meal (:meal @world)
                                                 :fw_day (r/resource (day-map {:plan [bad]}))}})))))
  (testing "the fixture pair itself assembles"
    (is (map? (ca/run-all {:kinds {:fw_meal (:meal @world)
                                   :fw_day (:day @world)}})))))

;; ── 4. the audit ─────────────────────────────────────────────────────────────────

(defn- meal-is-real [opts]
  (g/guard (merge {:name :meal-is-real
                   :explain "That meal is not on the list."
                   :judges [:meal_id] :reads [:fw_meal]
                   :open "Which meals exist is read at the write."
                   :remedies [:fw_day/plan]
                   :check (fn [_ _ _] (t/allow))}
                  opts)))

(defn- audit [plan-guards]
  (let [{:keys [warnings info]} (checks/run-all (r/resource (day-map {:plan plan-guards})))]
    (into warnings info)))

(defn- lines [tag ws] (filter #(str/starts-with? % tag) ws))

(deftest the-audit-names-unjudged-refs-and-convertible-guards
  (testing "[ref-unjudged]: nothing at the door reads the ref's kind"
    (is (= 1 (count (lines "[ref-unjudged] action plan field meal_id" (audit [])))))
    (is (empty? (lines "[ref-unjudged]" (audit [meal-is-listed]))))
    (is (empty? (lines "[ref-unjudged]"
                       (:warnings (checks/run-all
                                   (r/resource (assoc-in (day-map {:plan []})
                                                         [:actions :plan :waives]
                                                         #{:ref-unjudged}))))))
        "a door that may name any meal says so, and is not flagged"))
  (testing "[convertible-guard]: a code guard reading one kind through one ref"
    (let [ws (audit [(meal-is-real {})])]
      (is (= 1 (count (lines "[convertible-guard] info: guard meal-is-real on plan" ws))))
      (is (empty? (lines "[convertible-guard]"
                         (:warnings (checks/run-all (r/resource (day-map {:plan [(meal-is-real {})]}))))))
          "an info line is listed, never warned")
      (is (empty? (lines "[ref-unjudged]" ws)))))
  (testing "the same guard with :stays-code is listed as info, not flagged"
    (let [ws (audit [(meal-is-real {:stays-code "It reads two hops."})])]
      (is (empty? (lines "[convertible-guard]" ws)))
      (is (= ["[stays-code] info: guard meal-is-real on plan reads fw_meal over meal_id and stays code: It reads two hops."]
             (lines "[stays-code]" ws)))))
  (testing "a follow over the same field on the door covers the read"
    (is (empty? (lines "[convertible-guard]" (audit [meal-is-listed (meal-is-real {})])))))
  (testing "a :stays-code that is not a sentence is an error"
    (is (thrown-with-msg? Exception #"\[stays-code\]"
                          (r/resource (day-map {:plan [(meal-is-real {:stays-code " "})]}))))))
