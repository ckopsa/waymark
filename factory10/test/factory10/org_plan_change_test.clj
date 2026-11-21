(ns factory10.org-plan-change-test
  "The change plan (ticket be5f631d): a person files an org_plan with
  purpose change on an org that is live, and it seats its onboarding
  seat as a found plan does. A change plan for an org that is not live,
  a second open plan for an org, and a found plan for a live org are
  each refused.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.dev :as dev]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-21T08:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :type :human :display "colton"}))

(defn- template []
  (edn/read-string (slurp (io/resource "seats/onboarding.edn"))))

(defn- engine []
  (dev/scratch! (conj (vec (main/resources)) caps/capability)
                {:now-fn (constantly t0)}))

(defn- power!
  "The template's bench powers, its model and a runner link in that
  model's pool, registered on `eng`, as the house has them."
  [eng]
  (doseq [token (into (sorted-set)
                      (comp (map :kind) (filter #(str/includes? % ".")))
                      (get-in (template) [:seat :scope]))]
    (inv/create! eng :capability
                 {:token token
                  :description (str token " through Gate.")
                  :enforced_by "gate-mcp"}
                 {:principal colton}))
  (let [m (:row (inv/create! eng :model
                             {:name (get-in (template) [:template :model])
                              :display "Frontier" :vendor "anthropic" :tier "frontier"
                              :price_input_per_mtok 5M :price_output_per_mtok 25M
                              :price_cache_read_per_mtok 0.5M
                              :price_cache_write_per_mtok 6.25M}
                             {:principal colton}))]
    (inv/create! eng :runner_link
                 {:provider "claude_routine"
                  :fire_url "https://routines.example/fire/trig_onboarding"
                  :fire_token "a-fire-token-that-is-long-enough-1234"
                  :pools [{:kind "model" :id (str (:id m))}]}
                 {:principal colton}))
  eng)

(defn- rows-of [eng kind where]
  (let [rd (get (inv/resources eng) kind)]
    (mapv #(inv/decode-row rd %)
          (store/with-tx (:storage eng)
            (fn [tx] (vec (store/query-rows (:storage eng) tx kind where
                                            {:limit 1000})))))))

(defn- file!
  "An org_plan colton files for greenleaf. → the create's answer."
  [eng input]
  (inv/create! eng :org_plan
               (merge {:name "greenleaf" :brief "A six-person landscaping business."} input)
               {:principal colton}))

(defn- live!
  "greenleaf's found plan, walked live. → its id."
  [eng]
  (walk/live! eng (:id (:row (file! eng {}))) colton))

(defn- refused-by
  "The guard that refused the create of `input`, or nil when it was not."
  [eng input]
  (try (file! eng input) nil
       (catch clojure.lang.ExceptionInfo e
         (some-> (:guard (ex-data e)) name keyword))))

(def ^:private a-change
  {:purpose "change" :brief "Connect it to my mail."})

(deftest a-change-plan-on-a-live-org-is-filed-and-seated
  (let [eng (engine)
        _ (live! eng)
        _ (power! eng)
        plan (:row (file! eng a-change))
        seats (rows-of eng :seat {:name "onboarding-greenleaf"})]
    (testing "the plan is filed as a change, the person's"
      (is (some? plan))
      (is (= "change" (org-plan/purpose-of plan)))
      (is (= "drafting" (some-> (:state plan) name)))
      (is (= "colton" (str (get-in plan [:data :owner])))))
    (testing "and its onboarding seat is seated, as a found plan's is"
      (is (= 1 (count seats)) (str (get-in plan [:data :onboarding_note])))
      (is (= "active" (some-> (:state (first seats)) name))))))

(deftest the-template-still-fits-the-seat-door
  (let [seat (edn/read-string (str/replace (pr-str (:seat (template))) "{{org}}" "greenleaf"))]
    (is (<= (count (:charter seat)) 1200) (str (count (:charter seat)) " charter characters"))
    (is (<= (count (:instructions seat)) 2000) (str (count (:instructions seat)) " instruction characters"))))

(deftest a-change-plan-names-a-live-org
  (is (= :a-change-names-a-live-org (refused-by (engine) a-change))))

(deftest an-org-has-one-open-plan
  (let [eng (engine)]
    (live! eng)
    (file! eng a-change)
    (is (= :one-plan-per-name (refused-by eng a-change)))))

(deftest a-live-org-is-changed-not-founded-again
  (let [eng (engine)]
    (live! eng)
    (is (= :a-live-org-is-not-founded-again (refused-by eng {})))))
