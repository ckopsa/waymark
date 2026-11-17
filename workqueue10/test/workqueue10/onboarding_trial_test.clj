(ns workqueue10.onboarding-trial-test
  "The `onboarding-trial` seed and its count (ticket b5518453): the seed
  walks the factory seed's steps by reference with `trial` and fixed
  params, a walled boot makes its bench and mail at the wall, and the
  count reads only what happened after the seed.
  No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.onboarding-trial-test"
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.roles :as roles]
            [waymark10.server.runner-links :as runner-links]
            [waymark10.server.seed :as seed]
            [waymark10.server.store.memory :as memory]
            [workqueue10.main :as main]
            [workqueue10.onboarding-trial :as trial]))

(def ^:private wall "http://wall.invalid/mcp")

(defn- booted []
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (main/factory-only-resources)
                            :suppress-mirror-refresh true})]
    (runner-links/ensure-providers! eng)
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    eng))

(defn- server [eng nm]
  (first (filter #(= nm (get-in % [:data :name])) (dev/rows eng :mcp_server))))

(deftest the-seed-is-the-factory-seed-with-trial
  (let [factory (seed/read-seed "factory")
        s (seed/read-seed trial/seed-name)]
    (is (nil? (:extends s)))
    (is (= (:steps factory) (:steps s)) "the factory's steps, not a copy")
    (is (= "Riverside Vet Clinic's owner" (get-in s [:cast :owner :display])))
    (is (= (get-in factory [:cast :claude]) (get-in s [:cast :claude])))
    (is (true? (get-in s [:params :trial])))
    (is (= #{"bench" "mail"} (set (map :server (:walled s)))))
    (is (every? (set (keys (get-in s [:rehearsal :effects])))
                ["bench__prepare" "bench__read" "bench__find" "bench__edit"
                 "bench__edit_many" "bench__check" "bench__submit"
                 "bench__feedback" "bench__merge" "bench__create_repo"
                 "bench__artifact" "mail__send"]))))

(deftest a-seed-extending-itself-is-refused
  (is (thrown? clojure.lang.ExceptionInfo
               (seed/extended {:seed "loop" :extends "loop"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (seed/extended {:seed "orphan" :extends "no-such-seed"}))))

(deftest a-walled-boot-makes-the-trial-bench-and-mail-at-the-wall
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed trial/seed-name) {:params {} :wall-url wall})]
    (is (true? (:seeded out)) (pr-str out))
    (testing "the trial's own rows, at the wall's address"
      (is (= wall (get-in (server eng "bench") [:data :url])))
      (is (= wall (get-in (server eng "mail") [:data :url])))
      (is (= 1 (count (filter #(= "bench" (get-in % [:data :name])) (dev/rows eng :mcp_server)))))
      (is (= "why" (some #(when (= "bench.merge" (:power %)) (:approval %))
                         (get-in (server eng "bench") [:data :powers])))))
    (is (seed/trial-marked? eng trial/seed-name))
    (testing "the count reads nothing the seed did"
      (let [c (trial/count-of eng (trial/owner-of))]
        (is (= "trial-owner" (:owner c)))
        (is (= [] (:owner_moves c)))
        (is (= 0 (get-in c [:totals :owner_steps])))))))

(deftest the-tally-counts-the-owner-and-the-refusals
  (let [c (trial/tally
           {:owner "o"
            :log [{:kind :org_plan :resource-id "p1" :action :write_brief
                   :from-state :drafting :to-state :drafting :actor {:id "o"}}
                  {:kind :org_plan :resource-id "p1" :action :propose
                   :actor {:id "claude"}}
                  {:kind :held_call :resource-id "h1" :action :allow :actor "o"}]
            :held [{:id "h1" :state :done :data {:tool "bench__merge" :decided_by "o"}}]
            :sittings [{:id "s1" :data {:seat "seat-1"
                                        :refusal_log [{:guard "plan-agreed"
                                                       :remedies ["org_plan.agree"]
                                                       :door "go_live"}]}}]
            :asks [{:id "a1" :state :approved}]})]
    (is (= ["write_brief" "allow"] (mapv :door (:owner_moves c))))
    (is (= ["p1" "h1"] (mapv :row (:owner_moves c))))
    (is (= {:owner_steps 2 :owner_row_moves 2 :held_calls 1
            :held_calls_allowed_by_owner 1 :refusals 1 :rejections 0
            :agent_calls 0 :approval_requests 1}
           (:totals c)))
    (is (= {:sitting "s1" :seat "seat-1" :guard "plan-agreed"
            :remedies ["org_plan.agree"] :door "go_live" :kind nil :type nil :at nil}
           (first (:refusals c))))))

(deftest the-first-trial-counts-calls-not-row-moves
  ;; ticket 09053f55: write_brief (the engine seats the house in the
  ;; same call), offer_key, and one agree_many over five plan parts
  (let [own (fn [c kind row door from]
              {:kind kind :resource-id row :action door :from-state from
               :to-state :x :actor {:id "o"} :correlation-id c})
        c (trial/tally
           {:owner "o"
            :log (into [(own "c1" :seat "house" :create nil)
                        (own "c1" :org_plan "p1" :write_brief :drafting)
                        (own "c2" :seat "house" :offer_key :active)]
                       (for [i (range 5)]
                         (own "c3" :plan_part (str "part-" i) :agree :proposed)))
            :held []
            :sittings [{:id "s1"
                        :data {:seat "onboarding-house"
                               :served {:waymark_invoke {:calls 9 :bytes 10}
                                        :waymark_get {:calls 3 :bytes 10}}
                               :refusal_log [{:guard "built-in-home" :door "create"}
                                             {:type "no-repo-policy" :door "submit"}]
                               :rejection_log [{:type "schema-invalid" :status 422
                                                :kind "seat" :door "offer_key"
                                                :errors ["key"]}
                                               {:type "schema-invalid" :status 422
                                                :kind "plan_part" :door "create"
                                                :errors ["home"]}]}}]
            :asks []})]
    (is (= {:owner_steps 3 :owner_row_moves 7 :refusals 2 :rejections 2
            :agent_calls 12}
           (select-keys (:totals c) [:owner_steps :owner_row_moves :refusals
                                     :rejections :agent_calls])))
    (is (= [true false false false false false false false]
           (mapv :by_engine (:owner_moves c))))
    (is (= ["key"] (:errors (first (:rejections c)))))
    (is (= "offer_key" (:door (first (:rejections c)))))))

(deftest the-count-starts-after-the-marker
  (is (= [{:resource-id "x"}]
         (trial/after-the-seed [{:resource-id "a"}
                                {:resource-id "seed-onboarding-trial"}
                                {:resource-id "x"}])))
  (is (= [] (trial/after-the-seed [{:resource-id "a"}]))))
