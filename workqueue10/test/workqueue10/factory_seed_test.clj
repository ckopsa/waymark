(ns workqueue10.factory-seed-test
  "The `factory` seed (epic 00680418, T5): on a fresh factory-only
  engine, booted as `start!` boots one, the seed makes the owner and
  the agent acting for them, both starter judgments promoted, the
  bench, the repository's policy, the pull link and the model, and the
  four seats active and held for that model; a second load skips it.
  No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.factory-seed-test"
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.roles :as roles]
            [waymark10.server.runner-links :as runner-links]
            [waymark10.server.seed :as seed]
            [waymark10.server.store.memory :as memory]
            [workqueue10.main :as main]))

(def ^:private params
  {:owner "tim"
   :repo "tim/widgets"
   ;; nothing listens here: the bench row is born dark
   :bench_url "http://127.0.0.1:9/mcp"
   :model "claude-opus-5"
   :pull_token "a-pull-token-0123456789"})

(defn- booted
  "A factory-only engine with the boot rows the seed finds: the
  providers, the factory roles and domain `factory`."
  []
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (main/factory-only-resources)
                            :suppress-mirror-refresh true})]
    (runner-links/ensure-providers! eng)
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    eng))

(defn- state-of [row] (name (:state row)))

(defn- by-id [eng kind id]
  (first (filter #(= (str id) (str (:id %))) (dev/rows eng kind))))

(defn- named [eng kind n]
  (first (filter #(= n (get-in % [:data :name])) (dev/rows eng kind))))

(deftest the-factory-seed-stands-a-factory-up-once
  (let [eng (booted)
        factory (seed/read-seed "factory")
        out (seed/load! eng factory {:params params})
        model-id (str (get-in out [:refs :model :id]))
        domain-id (str (:id (domains/domain-named eng "factory")))]
    (is (true? (:seeded out)) (pr-str out))
    (testing "the owner, and the agent acting for them"
      (is (some? (by-id eng :member "tim")))
      (is (= "tim" (get-in (by-id eng :member "claude") [:data :acts_for]))))
    (testing "both judgments are promoted"
      (is (= ["promoted" "promoted"] (mapv state-of (dev/rows eng :judgment)))))
    (testing "the bench, the policy, the pull link and the model"
      (is (= "http://127.0.0.1:9/mcp" (get-in (named eng :mcp_server "bench") [:data :url])))
      (let [policy (first (dev/rows eng :repo_policy))]
        (is (= "tim/widgets" (get-in policy [:data :repository])))
        (is (= ["tests"] (get-in policy [:data :required_checks])))
        (is (= "tests.yml" (get-in policy [:data :test :workflow])))
        (is (= "only" (get-in policy [:data :test :input]))))
      (let [link (by-id eng :runner_link (get-in out [:refs :pull :id]))]
        (is (= "pull" (get-in link [:data :delivery])))
        (is (= [(str (:id link))]
               (get-in (by-id eng :model model-id) [:data :runners])))))
    (testing "the four seats are active and held for the model"
      (doseq [n ["mayor" "code-seat" "draft-judge" "sitting-judge"]
              :let [s (named eng :seat n)]]
        (is (= "active" (some-> s state-of)) n)
        (is (= [model-id] (mapv str (get-in s [:data :held_for]))) n))
      (doseq [n ["mayor" "draft-judge" "sitting-judge"]]
        (is (= domain-id (str (get-in (named eng :seat n) [:data :domain]))) n)))
    (testing "the cracks door answers"
      (let [resp ((engine/handler eng)
                  {:request-method :get :uri "/api/-/cracks"
                   :headers {"x-waymark-principal" "tim"
                             "x-waymark-actor-type" "human"}})]
        (is (= 200 (:status resp)))))
    (testing "a second load skips it"
      (is (false? (:seeded (seed/load! eng factory {:params params}))))
      (is (= 4 (count (dev/rows eng :seat)))))))
