(ns factory10.org-plan-onboarding-seat-test
  "Filing an org_plan seats its onboarding seat from the template
  (ticket 6b49540c): a person's create yields the plan and an active,
  keyless onboarding-<name> fenced to the org and held for the
  template's model, and next_step names offer_key. An active seat of
  that name the filer holds is reused; another person's refuses the
  create; retiring the plan parks it. With no bench powers registered,
  the plan is filed alone and next_step says so.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.dev :as dev]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-21T08:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :type :human}))

(def ^:private dana (t/principal {:id "dana" :type :human}))

(defn- template []
  (edn/read-string (slurp (io/resource "seats/onboarding.edn"))))

(defn- powered-engine
  "An engine with the template's bench powers registered, as the house
  has them, and the model the template names."
  []
  (let [eng (dev/scratch! (conj (vec (main/resources)) caps/capability)
                          {:now-fn (constantly t0)})]
    (doseq [token (into (sorted-set)
                        (comp (map :kind) (filter #(str/includes? % ".")))
                        (get-in (template) [:seat :scope]))]
      (inv/create! eng :capability
                   {:token token
                    :description (str token " through Gate.")
                    :enforced_by "gate-mcp"}
                   {:principal colton}))
    (inv/create! eng :model
                 {:name (get-in (template) [:template :model])
                  :display "Frontier" :vendor "anthropic" :tier "frontier"
                  :price_input_per_mtok 5M :price_output_per_mtok 25M
                  :price_cache_read_per_mtok 0.5M
                  :price_cache_write_per_mtok 6.25M}
                 {:principal colton})
    eng))

(defn- rows-of [eng kind where]
  (let [rd (get (inv/resources eng) kind)]
    (mapv #(inv/decode-row rd %)
          (store/with-tx (:storage eng)
            (fn [tx] (vec (store/query-rows (:storage eng) tx kind where
                                            {:limit 1000})))))))

(defn- seats-named [eng nm] (rows-of eng :seat {:name nm}))

(defn- plan-named [eng org] (first (rows-of eng :org_plan {:name org})))

(defn- step-sentence
  "The plan's computed next_step sentence: a stored row carries no
  computed field (ticket ebb60ce4)."
  [plan]
  (str (:sentence ((get-in org-plan/org-plan [:computed :next_step :fn]) plan {}))))

(defn- file! [eng who org]
  (:row (inv/create! eng :org_plan
                     {:name org :brief "A six-person landscaping business."}
                     {:principal who})))

(defn- model-id [eng]
  (str (:id (first (rows-of eng :model {})))))

(defn- hand-seat!
  "onboarding-<org>, opened by hand as `who`."
  [eng who org]
  (:row (inv/create! eng :seat
                     (-> (edn/read-string
                          (str/replace (pr-str (:seat (template))) "{{org}}" org))
                         (assoc :name (str "onboarding-" org)
                                :held_for [(model-id eng)]))
                     {:principal who})))

(deftest a-persons-create-seats-the-onboarding-seat
  (let [eng (powered-engine)
        _ (file! eng colton "greenleaf")
        plan (plan-named eng "greenleaf")
        [seat & more] (seats-named eng "onboarding-greenleaf")]
    (testing "the plan is filed and its seat born active, the filer's"
      (is (some? plan))
      (is (some? seat))
      (is (empty? more))
      (is (= "active" (some-> (:state seat) name)))
      (is (= "colton" (str (get-in seat [:data :owner])))))
    (testing "held for the template's model"
      (is (= [(model-id eng)] (mapv str (get-in seat [:data :held_for])))))
    (testing "its scope fenced to the org"
      (let [by-kind (into {} (map (juxt (comp str :kind) identity))
                          (get-in seat [:data :scope]))]
        (is (= "greenleaf" (get-in by-kind ["org_plan" :filter :name])))
        (is (= "orgs/greenleaf/**" (get-in by-kind ["bench.edit" :filter :path])))))
    (testing "keyless: next_step tells the person to offer it a key"
      (is (str/includes? (step-sentence plan) "offer_key")))))

(deftest an-active-seat-the-filer-holds-is-reused
  (let [eng (powered-engine)
        _ (hand-seat! eng colton "greenleaf")
        _ (file! eng colton "greenleaf")]
    (is (= 1 (count (filter #(= "active" (some-> (:state %) name))
                            (seats-named eng "onboarding-greenleaf")))))
    (is (str/includes? (step-sentence (plan-named eng "greenleaf")) "offer_key"))))

(deftest another-persons-seat-refuses-the-create
  (let [eng (powered-engine)
        _ (hand-seat! eng dana "greenleaf")
        refused (try (file! eng colton "greenleaf") nil
                     (catch Exception e e))]
    (is (some? refused) "the create is refused")
    (is (str/includes? (pr-str (ex-data refused)) "onboarding")
        (pr-str (ex-data refused)))
    (is (nil? (plan-named eng "greenleaf")))))

(deftest retiring-the-plan-parks-its-seat
  (let [eng (powered-engine)
        plan (file! eng colton "greenleaf")]
    (inv/invoke! eng :org_plan (str (:id plan)) :retire
                 {:why "We sold the business."} {:principal colton})
    (is (= ["parked"] (mapv #(some-> (:state %) name)
                            (seats-named eng "onboarding-greenleaf"))))))

(deftest with-no-bench-powers-the-plan-is-filed-alone
  (let [eng (dev/scratch! (main/resources) {:now-fn (constantly t0)})
        _ (file! eng colton "greenleaf")]
    (is (some? (plan-named eng "greenleaf")))
    (is (empty? (seats-named eng "onboarding-greenleaf")))
    (is (= "No onboarding seat: this house has no bench powers"
           (step-sentence (plan-named eng "greenleaf"))))))
