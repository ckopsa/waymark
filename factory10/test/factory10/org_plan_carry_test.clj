(ns factory10.org-plan-carry-test
  "A base move that leaves the org alone carries the findings (ticket
  ce8a4e17): the findings pass asks the forge whether org_dir changed
  between the audited head and the change's new head. A going_live plan
  whose org did not change keeps its findings and its yes, and the merge
  pass takes that yes for the new head with no recheck; one whose org
  changed is proposed again, and its finding says so. Under base_follow
  when_org_dir_changes a simulated plan keeps its findings the same way.

  No database: a memory engine is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(defn- row-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx kind (str id) {})))))

(defn- filed!
  "A plan the owner filed as greenleaf, with `data` beside. → its id."
  [eng data]
  (str (:id (:row (inv/create! eng :org_plan (merge {:name "greenleaf"} data)
                               {:principal the-owner})))))

(defn- simulated!
  "The drafting plan `id` proposed with its change at `walk/a-head`,
  flipped live and simulated there. → the id."
  [eng id]
  (walk/proposed! eng id the-owner)
  (walk/simulated! eng id walk/a-head))

(defn- a-forge
  "A forge with no artifact that answers `changed?` to every path
  compare, each recorded in `asked` as [from to path]."
  [changed? asked]
  (reify
    forge/ForgeArtifact
    (forge-artifact [_ _ _] nil)
    forge/ForgePathDiff
    (forge-path-changed? [_ _ from to path]
      (swap! asked conj [from to path])
      changed?)))

(defn- pass!
  "One findings pass over `source`, every moved head a base move."
  [eng source]
  (forge/findings-pass! eng source {} (fn [& _] nil)
                        {:base-move? (constantly true)}))

(defn- moved!
  "The change of plan `id` brought forward from base to `head`."
  [eng id head]
  (inv/invoke! eng :change (str (get-in (row-of eng :org_plan id) [:data :change]))
               :refresh_head {:head_sha head} walk/the-pull-request))

(defn- state [eng id] (name (:state (row-of eng :org_plan id))))

(deftest a-going-live-plan-keeps-its-findings-when-the-base-move-left-the-org-alone
  (let [eng (an-engine)
        id (walk/going-live! eng (filed! eng {}) the-owner)
        asked (atom [])
        data #(:data (row-of eng :org_plan id))]
    (moved! eng id head-2)
    (let [census (pass! eng (a-forge false asked))]
      (is (= 1 (:carried census)))
      (is (nil? (:rechecked census))))
    (is (= [[walk/a-head head-2 "orgs/greenleaf"]] @asked)
        "the forge was asked of the org's directory, from the approved head to the new head")
    (testing "the findings and the yes move to the new head"
      (is (= "going_live" (state eng id)))
      (is (= head-2 (:findings_sha (data))))
      (is (= head-2 (:dry_week_sha (data))) "the week moves with them (ticket 5c4bf05c)")
      (is (= head-2 (:approved_head (data)))))
    (testing "the merge pass takes the owner's yes for the new head: it waits on CI alone"
      (let [change (row-of eng :change (:change (data)))]
        (is (= head-2 (::bench/go-live-yes
                       (first (#'bench/go-live-yeses! eng [change])))))))
    (testing "a second pass leaves it be"
      (is (nil? (:carried (pass! eng (a-forge false asked)))))
      (is (= 1 (count @asked))))))

(deftest a-going-live-plan-whose-org-the-base-move-changed-is-checked-again
  (let [eng (an-engine)
        id (walk/going-live! eng (filed! eng {}) the-owner)
        data #(:data (row-of eng :org_plan id))]
    (moved! eng id head-2)
    (is (= 1 (:rechecked (pass! eng (a-forge true (atom []))))))
    (is (= "proposed" (state eng id)))
    (is (nil? (:approved_head (data))))
    (is (some #(and (= "head_left" (str (:key %)))
                    (str/includes? (str (:sentence %)) "changed the org")
                    (str/includes? (str (:sentence %)) (subs head-2 0 7)))
              (:findings (data)))
        "the finding says the base move changed the org, and the audit will read the new head")))

(deftest a-simulated-plan-under-when-org-dir-changes-keeps-its-findings
  (testing "under when_org_dir_changes, a base move that left the org alone keeps the plan simulated"
    (let [eng (an-engine)
          id (simulated! eng (filed! eng {:base_follow "when_org_dir_changes"}))
          data #(:data (row-of eng :org_plan id))]
      (moved! eng id head-2)
      (is (= 1 (:carried (pass! eng (a-forge false (atom []))))))
      (is (= "simulated" (state eng id)))
      (is (= head-2 (:findings_sha (data))))))
  (testing "under when_org_dir_changes, a move that changed the org is checked again"
    (let [eng (an-engine)
          id (simulated! eng (filed! eng {:base_follow "when_org_dir_changes"}))]
      (moved! eng id head-2)
      (is (= 1 (:rechecked (pass! eng (a-forge true (atom []))))))
      (is (= "proposed" (state eng id)))))
  (testing "under the default, the same move is checked again and the forge is not asked"
    (let [eng (an-engine)
          id (simulated! eng (filed! eng {}))
          asked (atom [])]
      (moved! eng id head-2)
      (is (= 1 (:rechecked (pass! eng (a-forge false asked)))))
      (is (= "proposed" (state eng id)))
      (is (empty? @asked)))))

;; ── a plan the audit checked, with no film (ticket 5c4bf05c) ─────────

(def ^:private head-3 "3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f")

(defn- weeked!
  "The audit's run at `head` on plan `id`: no findings, and a clean week."
  [eng id head]
  (inv/invoke! eng :org_plan id :record_findings
               {:head_sha head :findings [] :dry_week walk/a-week}
               walk/the-engine))

(deftest a-plan-the-audit-checked-follows-the-audit-across-base-moves
  (let [eng (an-engine)
        id (filed! eng {:base_follow "when_org_dir_changes"})
        data #(:data (row-of eng :org_plan id))]
    (walk/proposed! eng id the-owner)
    (weeked! eng id walk/a-head)
    (testing "findings and a week of the change's head simulate the plan"
      (is (= 1 (:simulated (pass! eng (a-forge false (atom []))))))
      (is (= "simulated" (state eng id))))
    (testing "a base move that left the org alone carries the week forward"
      (moved! eng id head-2)
      (is (= 1 (:carried (pass! eng (a-forge false (atom []))))))
      (is (= "simulated" (state eng id)))
      (is (= head-2 (:findings_sha (data))))
      (is (= head-2 (:dry_week_sha (data)))))
    (testing "one that changed the org sends it back to proposed until the audit records the new head"
      (moved! eng id head-3)
      (is (= 1 (:rechecked (pass! eng (a-forge true (atom []))))))
      (is (= "proposed" (state eng id)))
      (is (nil? (:simulated (pass! eng (a-forge true (atom []))))))
      (is (= "proposed" (state eng id))))
    (testing "the audit's week at the new head simulates it again"
      (weeked! eng id head-3)
      (is (= 1 (:simulated (pass! eng (a-forge true (atom []))))))
      (is (= "simulated" (state eng id))))))
