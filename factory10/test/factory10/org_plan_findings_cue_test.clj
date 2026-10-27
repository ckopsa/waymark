(ns factory10.org-plan-findings-cue-test
  "An onboarding seat hears its own plan's findings (ticket 24c4feba):
  the template's :wakes and inbox cue match every findings door on its
  org's plan and not on another org's, and the plan's computed
  `findings_pending` is true between a push and the findings recorded
  at it.

  No database: a memory engine is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.wakes :as wakes]
            [waymark10.types :as t]))

(def ^:private repo "ckopsa/waymark")
(def ^:private head-1 "2fba27a0c1d2e3f405162738495a6b7c8d9e0f12")
(def ^:private head-2 "a667ef3b1c2d3e4f5061728394a5b6c7d8e9f012")

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(def ^:private the-forge {:principal mirror/source-principal})

(def ^:private the-pull-request
  {:principal (assoc mirror/source-principal :subscription "pull_request")})

(def ^:private findings-doors
  [:record_findings :record_film_findings])

(defn- an-engine []
  (engine/engine {:storage (memory/storage)
                  :resources (vec (main/resources))}))

(defn- a-change! [eng number head]
  (str (:id (:row (inv/create! eng :change
                               {:change_id (str "github:" repo "#" number)
                                :repository repo
                                :number number
                                :title "An org's pull request"
                                :head_branch (str "orgs/change-" number)
                                :base_branch "main"
                                :head_sha head}
                               the-forge)))))

(defn- a-plan!
  "A drafting plan its owner filed, linked to `change` by the engine."
  [eng org change]
  (let [id (str (:id (:row (inv/create! eng :org_plan {:name org}
                                         {:principal the-owner}))))]
    (inv/invoke! eng :org_plan id :link_change {:change change} the-forge)
    id))

(defn- load-row [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx kind (str id) {})))))

(defn- onboarding-template [org]
  (let [t (edn/read-string (slurp (io/resource "seats/onboarding.edn")))]
    ;; the template filled for `org`: its :seat, and its :wakes beside it
    (edn/read-string (str/replace (pr-str (select-keys t [:seat :wakes])) "{{org}}" org))))

(defn- hears?
  "Does one of `entries` match `action` on the plan `id`, its filter
  judged against the row as the wake consumer judges it?"
  [eng entries action id]
  (boolean (some #(wakes/moved-under? eng :org_plan id (:filter %))
                 (wakes/matching-entries entries :org_plan action))))

(deftest the-seat-hears-its-own-plans-findings-and-no-other
  (let [eng (an-engine)
        greenleaf (a-plan! eng "greenleaf" (a-change! eng 101 head-1))
        acme (a-plan! eng "acme" (a-change! eng 102 head-2))
        t (onboarding-template "greenleaf")
        wake (:wakes t)
        cues (get-in t [:seat :inbox :cues])]
    (testing "a record_findings on its plan wakes the seat and cues its open sitting"
      (is (hears? eng wake :record_findings greenleaf))
      (is (hears? eng cues :record_findings greenleaf))
      (is (str/includes? (str (:note (first cues))) "next_step")))
    (testing "every findings door is heard"
      (doseq [a findings-doors]
        (is (hears? eng wake a greenleaf) (name a))
        (is (hears? eng cues a greenleaf) (name a))))
    (testing "another plan's findings do not"
      (doseq [a findings-doors]
        (is (not (hears? eng wake a acme)) (name a))
        (is (not (hears? eng cues a acme)) (name a))))))

(deftest findings-pending-is-true-between-a-push-and-its-findings
  (let [eng (an-engine)
        change (a-change! eng 101 head-1)
        id (a-plan! eng "greenleaf" change)
        read' (fn [kind rid] (load-row eng kind rid))
        computed (fn [f] ((get-in org-plan/org-plan [:computed f :fn])
                          (load-row eng :org_plan id) {:read read'}))]
    (testing "a pushed head with no findings at it is pending"
      (is (true? (computed :findings_pending)))
      (is (= head-1 (computed :findings_pending_head))))
    (testing "the findings at that head end it"
      (inv/invoke! eng :org_plan id :record_findings
                   {:head_sha head-1 :findings []} the-forge)
      (is (false? (computed :findings_pending)))
      (is (nil? (computed :findings_pending_head))))
    (testing "the next push opens it again, for the new head"
      (inv/invoke! eng :change change :refresh_head {:head_sha head-2} the-pull-request)
      (is (true? (computed :findings_pending)))
      (is (= head-2 (computed :findings_pending_head))))
    (testing "with no read lent it says nothing"
      (is (nil? ((get-in org-plan/org-plan [:computed :findings_pending :fn])
                 (load-row eng :org_plan id) {}))))))
