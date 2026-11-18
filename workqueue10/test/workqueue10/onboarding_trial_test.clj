(ns workqueue10.onboarding-trial-test
  "The `onboarding-trial` seed and its count (ticket b5518453): the seed
  walks the factory seed's steps by reference with `trial` and fixed
  params, a walled boot makes its bench and mail at the wall, and the
  count reads only what happened after the seed.
  No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.onboarding-trial-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp.bench :as mcp-bench]
            [waymark10.server.roles :as roles]
            [waymark10.server.runner-links :as runner-links]
            [waymark10.server.seed :as seed]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
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

(deftest the-house-plan-and-its-seat-work-in-the-trial-repository
  ;; ticket 36b1cd39: the first trial's submit was refused, its change
  ;; and its seat in ckopsa/waymark, which has no policy here
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed trial/seed-name) {:params {} :wall-url wall})
        named (fn [kind n] (first (filter #(= n (get-in % [:data :name])) (dev/rows eng kind))))
        house (str (:id (named :org_plan "house")))
        _ (inv/invoke! eng :org_plan house :write_brief
                       {:brief "A vet clinic: appointments, reminders and invoices."}
                       {:principal (t/principal {:id "trial-owner" :type :human})})
        seat (named :seat "onboarding-house")
        benches (filter #(str/starts-with? (str (:kind %)) "bench.")
                        (get-in seat [:data :scope]))
        reads? #(contains? #{"bench.find" "bench.read"} (str (:kind %)))]
    (is (true? (:seeded out)) (pr-str out))
    (is (some? seat) "write_brief seats onboarding-house")
    (testing "its writes name the trial's repository"
      (is (seq (remove reads? benches)))
      (doseq [e (remove reads? benches)]
        (is (= "trial/orgs" (get-in e [:filter :repo])) (str (:kind e)))))
    (testing "its reads name the trial's repository and the kit, once each"
      (doseq [e (filter reads? benches)]
        (is (= "trial/orgs,ckopsa/waymark" (get-in e [:filter :repo])) (str (:kind e)))))
    (testing "its change is minted in trial/orgs, which has a policy"
      (is (= {:repo "trial/orgs" :reference ["ckopsa/waymark"]}
             (mcp-bench/seat-repositories seat)))
      (is (some #(= "trial/orgs" (str (get-in % [:data :repository])))
                (filter #(= "active" (name (:state %))) (dev/rows eng :repo_policy)))))
    (testing "the step names no kit fallback"
      (is (not (str/includes? (str (get-in (named :org_plan "house")
                                           [:data :onboarding_note :sentence]))
                              "no orgs repository"))))))

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
    (is (= {:owner_steps 2 :held_calls 1 :held_calls_allowed_by_owner 1
            :refusals 1 :approval_requests 1}
           (:totals c)))
    (is (= {:sitting "s1" :seat "seat-1" :guard "plan-agreed"
            :remedies ["org_plan.agree"] :door "go_live" :kind nil :type nil :at nil}
           (first (:refusals c))))))

(deftest the-count-starts-after-the-marker
  (is (= [{:resource-id "x"}]
         (trial/after-the-seed [{:resource-id "a"}
                                {:resource-id "seed-onboarding-trial"}
                                {:resource-id "x"}])))
  (is (= [] (trial/after-the-seed [{:resource-id "a"}]))))
