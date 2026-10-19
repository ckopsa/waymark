(ns factory10.org-plan-audit-test
  "An org_plan names its change, and goes up only as audited (ticket
  db8b35bb): propose is refused with no change, and at a head the audit
  has not run on, and opens with the plan's own change once it has; the
  sit's link is the engine's door alone; a plan whose change is unnamed
  is still found by the change born from it, by the findings pass and by
  the go_live hold.

  No database: a memory engine is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [factory10.sources.forge :as forge]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private head-2 "a667ef3b1c2d3e4f5061728394a5b6c7d8e9f012")

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(defn- an-engine []
  (engine/engine {:storage (memory/storage)
                  :resources (vec (main/resources))}))

(defn- filed! [eng]
  (str (:id (:row (inv/create! eng :org_plan {:name "greenleaf"}
                               {:principal the-owner})))))

(defn- plan [eng id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx :org_plan id {})))))

(defn- refused? [f]
  (try (f) false (catch Exception _ true)))

(deftest a-plan-goes-up-only-audited-at-its-head
  (let [eng (an-engine)
        id (filed! eng)
        propose! #(inv/invoke! eng :org_plan id :propose % {:principal the-owner})
        state #(name (:state (plan eng id)))]
    (testing "with no change named, propose is refused"
      (is (refused? #(propose! {})))
      (is (= "drafting" (state))))
    (let [change (walk/a-change! eng id walk/a-head)]
      (testing "before the audit ran at the change's head, propose is refused"
        (is (refused? #(propose! {:change change})))
        (inv/invoke! eng :org_plan id :record_findings
                     {:head_sha head-2 :findings []} walk/the-engine)
        (is (refused? #(propose! {:change change})))
        (is (= "drafting" (state))))
      (testing "the sit's link names the change, and only the engine's hand takes it"
        (is (refused? #(inv/invoke! eng :org_plan id :link_change {:change change}
                                    {:principal the-owner})))
        (inv/invoke! eng :org_plan id :link_change {:change change} walk/the-engine)
        (is (= change (str (get-in (plan eng id) [:data :change]))))
        (is (refused? #(inv/invoke! eng :org_plan id :link_change {:change change}
                                    walk/the-engine))
            "a plan whose change is in play keeps it"))
      (testing "once the audit ran at the head, propose opens with the plan's own change"
        (inv/invoke! eng :org_plan id :record_findings
                     {:head_sha walk/a-head :findings []} walk/the-engine)
        (propose! {})
        (is (= "proposed" (state)))
        (is (= change (str (get-in (plan eng id) [:data :change]))))))))

(deftest an-unlinked-plan-is-found-by-the-change-born-from-it
  (let [eng (an-engine)
        id (filed! eng)
        change (walk/a-change! eng id head-2)
        source (reify forge/ForgeArtifact
                 (forge-artifact [_ _ artifact-name]
                   (when (= artifact-name (str "org-findings-greenleaf-" head-2))
                     {:greenleaf []})))]
    (is (nil? (get-in (plan eng id) [:data :change])))
    (testing "the findings pass lands findings at its change's head"
      (is (= 1 (:findings (forge/findings-pass! eng source {} (fn [& _] nil)))))
      (is (= head-2 (get-in (plan eng id) [:data :findings_sha]))))
    (testing "the go_live hold keys the plan by that change"
      (is (= id (str (:id (get (#'bench/org-plans eng ["drafting"]) change))))))))
