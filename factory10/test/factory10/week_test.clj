(ns factory10.week-test
  "The week report judged (ticket 7fba2e9b): a refused call, no held
  call and a zero payoff are each an error finding, a clean week has
  none, and the forge's findings pass records the checks and the
  compact week on the plan, so a stalling week blocks propose.

  No database: a memory engine is the whole world.

  Run: cd factory10 && clojure -M:test --focus factory10.week-test"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [factory10.sources.forge :as forge]
            [factory10.week :as week]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private held
  {:day 1 :as "world" :kind "garden_job" :action "request" :outcome "held"
   :held_call "h1" :detail "The owner books a job."})

(def ^:private moved
  {:day 2 :as "person" :kind "held_call" :action "allow" :outcome "moved"})

(def ^:private refused
  {:day 3 :as "mayor" :kind "garden_job" :action "book" :outcome "refused"
   :status 409 :guard "only-the-owner-books" :detail "Only the owner books a job."})

(def ^:private booked {:kind "garden_job" :state "booked" :count 1 :rows ["j1"]})

(def ^:private clean
  {:org "acme" :day_one "2026-10-05" :calls [held moved]
   :person_asks 1 :still_held 0 :goal booked})

(def ^:private stalling (update clean :calls conj refused))

(defn- keys-of [findings] (mapv :key findings))

(deftest a-clean-week-has-no-findings
  (is (= [] (week/judge-week clean)))
  (testing "string keys, as the artifact's JSON reads, judge the same"
    (is (= [] (week/judge-week (clojure.walk/stringify-keys clean))))))

(deftest a-refused-call-is-a-stall
  (let [[f :as found] (week/judge-week stalling)]
    (is (= ["week-stalls/0"] (keys-of found)))
    (is (= "error" (:severity f)))
    (is (= "day 3: garden_job book refused by only-the-owner-books (Only the owner books a job.)"
           (:sentence f))))
  (testing "a refusal's own remedies are the finding's remedy"
    (is (= "Ask the owner."
           (:remedy (first (week/judge-week
                            (update clean :calls conj
                                    (assoc refused :remedies ["Ask the owner."])))))))))

(deftest no-held-call-is-no-ask
  (let [found (week/judge-week (assoc clean :person_asks 0 :calls [moved]))]
    (is (= ["week-no-ask"] (keys-of found)))
    (is (= "Nothing in the week waits on the owner's tap." (:sentence (first found))))))

(deftest a-zero-goal-is-no-payoff
  (let [found (week/judge-week (assoc clean :goal (assoc booked :count 0 :rows [])))]
    (is (= ["week-no-payoff"] (keys-of found)))
    (is (= "By day 2 nothing is booked (garden_job)." (:sentence (first found))))))

(deftest the-artifact-says-what-it-carries
  (is (nil? (week/of-artifact {:acme []})) "an audit that hands no report adds nothing")
  (is (= ["week-missing"] (keys-of (:findings (week/of-artifact {:acme [] :week_report nil})))))
  (is (= "warning" (:severity week/missing)))
  (is (= [{:day 1 :as "world" :kind "garden_job" :action "request" :outcome "held"}
          {:day 2 :as "person" :kind "held_call" :action "allow" :outcome "moved"}]
         (:dry_week (week/of-artifact {"week_report" (clojure.walk/stringify-keys clean)})))))

;; ── the findings pass ───────────────────────────────────────────────

(def ^:private repo "ckopsa/waymark")
(def ^:private head-1 "2fba27a0c1d2e3f405162738495a6b7c8d9e0f12")
(def ^:private head-2 "a667ef3b1c2d3e4f5061728394a5b6c7d8e9f012")

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(def ^:private the-forge {:principal mirror/source-principal})

(defn- plan [eng id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx :org_plan id {})))))

(defn- artifacts [by-name]
  (reify forge/ForgeArtifact
    (forge-artifact [_ _repository artifact-name]
      (get @by-name artifact-name))))

(deftest a-stalling-week-blocks-propose
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})
        change (str (:id (:row (inv/create! eng :change
                                            {:change_id (str "github:" repo "#101")
                                             :repository repo
                                             :number 101
                                             :title "An org's pull request"
                                             :head_branch "orgs/change-101"
                                             :base_branch "main"
                                             :head_sha head-1}
                                            the-forge))))
        id (str (:id (:row (inv/create! eng :org_plan {:name "acme"}
                                        {:principal the-owner}))))
        _ (inv/invoke! eng :org_plan id :link_change {:change change} the-forge)
        by-name (atom {(str "org-findings-acme-" head-1)
                       {:acme [] :week_report (clojure.walk/stringify-keys stalling)}})
        pass! #(forge/findings-pass! eng (artifacts by-name) {} (fn [& _] nil))
        data #(:data (plan eng id))]
    (testing "the stall lands on the plan and propose is refused"
      (is (= 1 (:findings (pass!))))
      (is (= ["week-stalls/0"] (mapv :key (:findings (data)))))
      (is (= head-1 (:dry_week_sha (data))))
      (is (= "only-the-owner-books" (:guard (last (:dry_week (data))))))
      (is (thrown? Exception
                   (inv/invoke! eng :org_plan id :propose {} {:principal the-owner}))))
    (testing "a clean week at the next head records the week and opens propose"
      (inv/invoke! eng :change change :observe {:head_sha head-2} the-forge)
      (swap! by-name assoc (str "org-findings-acme-" head-2)
             {:acme [] :week_report clean})
      (is (= 1 (:findings (pass!))))
      (is (= [] (:findings (data))))
      (is (= head-2 (:dry_week_sha (data))))
      (is (= 2 (count (:dry_week (data)))))
      (inv/invoke! eng :org_plan id :propose {} {:principal the-owner})
      (is (= "proposed" (name (:state (plan eng id))))))))
