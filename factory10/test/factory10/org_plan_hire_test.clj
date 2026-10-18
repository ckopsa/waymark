(ns factory10.org-plan-hire-test
  "The hire plan (ticket 78f11fa7, M4a): the engine's open-hire! opens
  one org_plan per gap on a live org, with the live plan's owner and
  directory and a brief written from the gap; a second for the same
  gap key is refused while the first stands, an org with no live plan
  is refused, and no hand but the engine's opens one.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
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
  (let [id (str (:id (filed! eng)))]
    (inv/invoke! eng :org_plan id :propose {} {:principal the-owner})
    (inv/invoke! eng :org_plan id :hand_in_film
                 {:film_url "https://example.com/greenleaf.mp4"
                  :take_report "{}" :head_sha "5e469c6166edf6a3"}
                 {:principal the-owner})
    (inv/invoke! eng :org_plan id :attach_film
                 {:film_url "https://example.com/greenleaf.mp4"}
                 {:principal the-engine})
    (inv/invoke! eng :org_plan id :go_live {} {:principal the-owner})
    id))

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

(deftest a-plan-without-purpose-reads-as-found
  (is (= "found" (org-plan/purpose-of {:data {:name "greenleaf"}})))
  (is (= "found" (org-plan/purpose-of (filed! (engine))))))
