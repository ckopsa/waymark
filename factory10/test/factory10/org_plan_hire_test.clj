(ns factory10.org-plan-hire-test
  "The hire plan (ticket 78f11fa7, M4a): the engine's open-hire! opens
  one org_plan per gap on a live org, with the live plan's owner and
  directory and a brief written from the gap; a second for the same
  gap key is refused while the first stands, an org with no live plan
  is refused, and no hand but the engine's opens one.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.law-text]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-18T08:00:00Z"))

(defn- engine [] (dev/scratch! (main/resources) {:now-fn (constantly t0)}))

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(def ^:private the-engine
  (t/principal {:id "factory10-hire" :type :system :display "The engine"}))

(def ^:private an-agent
  (t/principal {:id "onboarding" :type :agent :acts-for "colton"
                :display "onboarding"}))

(def ^:private gap
  {:kind "invoice" :state "sent" :reason "no_holder"
   :row_ids ["i1" "i2" "i3"] :oldest_since "2026-10-11T08:00:00Z"
   :expected_seconds 604800 :key "greenleaf/invoice/sent"})

(defn- filed!
  "A plan the owner filed through the ordinary create. → its row."
  [eng]
  (:row (inv/create! eng :org_plan
                     {:name "greenleaf"
                      :brief "A six-person landscaping business."}
                     {:principal the-owner})))

(defn- live!
  "A found plan the owner walked to live. → its id."
  [eng]
  (walk/live! eng (:id (filed! eng)) the-owner))

(defn- hire! [eng who]
  (org-plan/open-hire! eng {:name "greenleaf" :gap gap} {:principal who}))

(deftest a-live-org-gets-a-drafting-hire-plan
  (let [eng (engine)
        _ (live! eng)
        made (hire! eng the-engine)
        row (:row made)]
    (is (some? row) (pr-str made))
    (is (= "drafting" (name (:state row))))
    (is (= "hire" (org-plan/purpose-of row)))
    (is (= "greenleaf" (get-in row [:data :name])))
    (is (= "colton" (str (get-in row [:data :owner]))))
    (is (= "orgs/greenleaf" (get-in row [:data :org_dir])))
    (is (= "greenleaf/invoice/sent" (get-in row [:data :gap :key])))
    (is (= (str "invoice rows wait in sent: 3 past their expected age, "
                "oldest since 2026-10-11T08:00:00Z; no seat holds the door out")
           (get-in row [:data :brief])))))

(deftest one-hire-plan-per-gap
  (let [eng (engine)
        _ (live! eng)
        first-plan (:row (hire! eng the-engine))]
    (testing "a second call for the same gap key is refused"
      (is (= :hire_open (:refused (hire! eng the-engine)))))
    (testing "a retired one lets a new one open"
      (inv/invoke! eng :org_plan (str (:id first-plan)) :retire
                   {:why "Hired a collections clerk."} {:principal the-owner})
      (let [again (hire! eng the-engine)]
        (is (some? (:row again)) (pr-str again))
        (is (not= (str (:id first-plan)) (str (:id (:row again)))))))))

(deftest no-live-plan-no-hire
  (let [eng (engine)]
    (filed! eng)
    (is (= :no_live_org (:refused (hire! eng the-engine))))))

(deftest only-the-engine-opens-a-hire-plan
  (let [eng (engine)]
    (live! eng)
    (is (= :engine_only (:refused (hire! eng an-agent))))
    (is (= :engine_only (:refused (hire! eng the-owner))))))

(deftest open-hire-is-a-declared-create-door
  (let [eng (engine)
        rdef (get (inv/resources eng) :org_plan)]
    (testing "discover's vocabulary and the law page name it"
      (is (contains? (inv/action-names rdef) "open_hire"))
      (is (contains? (inv/action-names rdef) "create"))
      (let [page (apply str (interpose "\n" (#'factory10.law-text/kind-section rdef)))]
        (is (re-find #"\*\*open_hire\*\*" page) page)
        (is (re-find #"Only the engine walks it" page) page)))
    (testing "the engine's call births a hire plan, and the log says open_hire"
      (live! eng)
      (let [made (hire! eng the-engine)]
        (is (= "open_hire" (some-> made :transition :action name)) (pr-str made))
        (is (= "hire" (org-plan/purpose-of (:row made))))))
    (testing "an agent's call at the door is refused not_yours"
      (let [e (try (inv/create! eng :org_plan
                                {:name "greenleaf" :gap (assoc gap :key "greenleaf/x/y")}
                                {:principal an-agent :action :open_hire})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :not_yours (some-> (:guard (ex-data e)) name keyword)))))
    (testing "the ordinary create is unchanged"
      (let [made (inv/create! eng :org_plan {:name "acme"} {:principal the-owner})]
        (is (= "create" (some-> made :transition :action name)))
        (is (= "found" (org-plan/purpose-of (:row made))))))))

(deftest a-stored-plan-created-without-purpose-reads-as-found
  (testing "the create stamps found on a plan filed without :purpose"
    (let [row (filed! (engine))]
      (is (= "found" (get-in row [:data :purpose])) (pr-str row))
      (is (= "found" (org-plan/purpose-of row)))))
  (testing "the boot's backfill stamps found on a stored plan with none"
    (is (= "found" (:purpose (#'org-plan/purpose-backfill {:name "greenleaf"}))))
    (is (= "hire" (:purpose (#'org-plan/purpose-backfill
                             {:name "greenleaf" :purpose "hire"}))))))
