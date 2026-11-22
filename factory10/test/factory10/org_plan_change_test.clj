(ns factory10.org-plan-change-test
  "The change plan (ticket be5f631d): a person files an org_plan with
  purpose change on an org that is live, and it seats its onboarding
  seat as a found plan does. A change plan for an org that is not live,
  a second open plan for an org, and a found plan for a live org are
  each refused. A change plan's change, born from it, takes the audit's
  findings and next_step as a found plan's does, and goes up (ticket
  cd12632c).

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [factory10.resources.org-plan :as org-plan]
            [factory10.resources.org-plan-audit :as audit]
            [factory10.sources.forge :as forge]
            [waymark10.dev :as dev]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
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

(def ^:private change-head-1 "2fba27a0c1d2e3f405162738495a6b7c8d9e0f12")
(def ^:private change-head-2 "a667ef3b1c2d3e4f5061728394a5b6c7d8e9f012")

(def ^:private a-kind-finding
  {:key "resource/garden_job/no-check" :severity "error" :class "resource"
   :subject "garden_job" :sentence "No one chases a late garden job."
   :remedy "Name who chases a late garden job."})

(defn- artifacts
  "A source whose one verb is the artifact read, over the atom `by-name`."
  [by-name]
  (reify forge/ForgeArtifact
    (forge-artifact [_ _repository artifact-name]
      (get @by-name artifact-name))))

(deftest a-change-plans-findings-reach-it-and-it-goes-up
  (let [eng (engine)
        _ (live! eng)
        id (str (:id (:row (file! eng a-change))))
        change (walk/linked! eng id change-head-1)
        by-name (atom {(str "org-findings-greenleaf-" change-head-1)
                       {:greenleaf [a-kind-finding]}})
        pass! #(forge/findings-pass! eng (artifacts by-name) {} (fn [& _] nil))
        plan #(store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx :org_plan id {})))
        step #((get-in org-plan/org-plan [:computed :next_step :fn]) (plan) {})]
    (testing "the change is the plan's own, on the plan's branch"
      (is (= change (str (get-in (plan) [:data :change])))))
    (testing "the audit's finding on the changed org lands and is next_step"
      (is (= 1 (:findings (pass!))))
      (is (= change-head-1 (str (get-in (plan) [:data :findings_sha]))))
      (is (= [(:key a-kind-finding)] (mapv :key (get-in (plan) [:data :findings]))))
      (is (= (:key a-kind-finding) (:key (step)))))
    (testing "a clean head empties next_step, and the change plan goes up"
      (inv/invoke! eng :change change :refresh_head {:head_sha change-head-2}
                   walk/the-pull-request)
      (swap! by-name assoc (str "org-findings-greenleaf-" change-head-2) {:greenleaf []})
      (is (= 1 (:findings (pass!))))
      (is (= change-head-2 (str (get-in (plan) [:data :findings_sha]))))
      (is (nil? (:key (step))))
      (walk/parts-agreed! eng id colton)
      (inv/invoke! eng :org_plan id :propose {} {:principal colton})
      (is (= "proposed" (some-> (:state (plan)) name)))
      (is (= "change" (org-plan/purpose-of (plan)))))))

(defn- req [h method uri headers body]
  (h (cond-> {:request-method method :uri uri :headers headers}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private person {"x-waymark-principal" "colton"})

(defn- granted!
  "The onboarding seat's sitter, granted go_live by colton. → its headers."
  [h]
  (let [agent {"x-waymark-principal" "onboarding" "x-waymark-actor-type" "agent"
               "x-waymark-acts-for" "colton"}
        asked (req h :post "/api/approval_requests" agent
                   {:task "Ask to change the org."
                    :scope [{:kind "org_plan" :actions ["go_live"]}]})
        approved (req h :post (str "/api/approval_requests/" (id-of asked) "/-/approve")
                      person nil)]
    (assert (= 200 (:status approved)) (pr-str (json approved)))
    (assoc agent "x-waymark-grant" (get-in (json approved) [:data :grant_id]))))

(deftest a-proposed-change-plan-is-simulated-and-goes-live-on-the-owners-tap
  (let [eng (engine)
        h (dev/handler eng)
        _ (live! eng)
        id (str (:id (:row (file! eng a-change))))
        _ (walk/proposed! eng id colton {:head change-head-1})
        plan #(store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx :org_plan id {})))]
    (testing "the audit's pass simulates a proposed change plan as a found one"
      (inv/invoke! eng :org_plan id :record_findings
                   {:head_sha change-head-1 :findings [] :dry_week walk/a-week}
                   walk/the-engine)
      (is (= 1 (:simulated (audit/simulate-pass! eng walk/the-engine {} (fn [& _] nil)))))
      (is (= "simulated" (some-> (:state (plan)) name)))
      (is (not (true? (get-in (plan) [:data :live_flag]))) "no second :live flip"))
    (let [asked (req h :post (str "/api/org_plans/" id "/-/go_live") (granted! h) {})
          held (:held_call (json asked))]
      (testing "the seat's go_live is held for the owner, saying what changes"
        (is (= 202 (:status asked)) (pr-str (json asked)))
        (is (= "simulated" (some-> (:state (plan)) name)))
        (let [why (str (get-in (json (req h :get (str "/api/held_calls/" held) person nil))
                               [:data :why]))]
          (is (str/includes? why "Change greenleaf? Allow merges the org's pull request at 2fba27a.") why)
          (is (not (str/includes? why "Make greenleaf live?")) why)))
      (testing "the owner's Allow approves the head the audit read at the tap"
        (let [allowed (req h :post (str "/api/held_calls/" held "/-/allow") person nil)]
          (is (= 200 (:status allowed)) (pr-str (json allowed))))
        (is (= "going_live" (some-> (:state (plan)) name)))
        (is (= change-head-1 (str (get-in (plan) [:data :approved_head]))))))))

(deftest an-org-has-one-open-plan
  (let [eng (engine)]
    (live! eng)
    (file! eng a-change)
    (is (= :one-plan-per-name (refused-by eng a-change)))))

(deftest a-live-org-is-changed-not-founded-again
  (let [eng (engine)]
    (live! eng)
    (is (= :a-live-org-is-not-founded-again (refused-by eng {})))))
