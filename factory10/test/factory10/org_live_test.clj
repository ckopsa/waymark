(ns factory10.org-live-test
  "A going_live org_plan reads live once the house has loaded its org
  (ticket 25c1520b, M2e-4): over a memory engine with orgs/example, a
  plan with no domain stays going_live; after the org's seed loads, with
  its mayor's seat in the org's domain, the pass marks it live with
  `mayor_seat`; a second pass changes nothing. No database.

  Run: cd factory10 && clojure -M:test --focus factory10.org-live-test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-live :as org-live]
            [factory10.org-plan-walk :as walk]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.org :as org-boot]
            [waymark10.server.roles :as roles]
            [waymark10.server.seed :as seed-load]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(defn- org-engine
  "A memory engine as the house boots one: the factory's kinds and
  example's, with the factory's roles and domain."
  []
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (into (vec (main/resources))
                                             ((requiring-resolve 'example.org/resources)))
                            :suppress-mirror-refresh true})]
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    eng))

(defn- seed-with-its-mayor
  "example's seed, with the plan's mayor seated in the org's domain."
  []
  (let [s (edn/read-string (slurp (io/resource "example/seed.edn")))
        clerk (:seat (first (:seats s)))]
    (-> s
        (update :domains (fn [ds] (mapv #(assoc % :ref :home) ds)))
        (update :seats conj {:seat (assoc clerk
                                          :name (:name walk/a-mayor)
                                          :domain [:ref :home])}))))

(defn- load-row [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- state-of [eng id] (name (:state (load-row eng :org_plan id))))

(deftest a-going-live-plan-reads-live-once-its-org-is-served
  (let [eng (org-engine)
        filed (:row (inv/create! eng :org_plan {:name "example"} {:principal the-owner}))
        id (walk/going-live! eng (:id filed) the-owner)]
    (testing "no domain named example: the plan stays going_live"
      (is (= 0 (org-live/mark-live-plans! eng)))
      (is (= "going_live" (state-of eng id))))
    (is (true? (:seeded (seed-load/load! eng (org-boot/loader-seed (seed-with-its-mayor)) {}))))
    (testing "the org is loaded with its mayor's seat: the plan is live"
      (is (= 1 (org-live/mark-live-plans! eng)))
      (let [plan (load-row eng :org_plan id)
            seat (get-in plan [:data :mayor_seat])]
        (is (= "live" (name (:state plan))))
        (is (some? seat))
        (is (= (:name walk/a-mayor) (get-in (load-row eng :seat seat) [:data :name])))))
    (testing "a second pass changes nothing"
      (let [before (load-row eng :org_plan id)]
        (is (= 0 (org-live/mark-live-plans! eng)))
        (is (= before (load-row eng :org_plan id)))))))

(deftest a-mayor-outside-the-orgs-domain-does-not-make-it-live
  (let [eng (org-engine)
        filed (:row (inv/create! eng :org_plan {:name "example"} {:principal the-owner}))
        id (walk/going-live! eng (:id filed) the-owner)
        s (edn/read-string (slurp (io/resource "example/seed.edn")))]
    (seed-load/load! eng (org-boot/loader-seed s) {})
    (is (= 0 (org-live/mark-live-plans! eng)))
    (is (= "going_live" (state-of eng id)))))
