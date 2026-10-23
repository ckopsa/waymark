(ns factory10.org-plan-film-ready-test
  "A plan shows what filming waits on (ticket f1c6a494): `film_ready`
  lists its items in order, a stale scene sha shows scene_at_head not
  ok and the waiting_on verdict names it, and a ready plan shows every
  item ok.

  No database: the computed field and the block are pure over the rows
  handed them.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.server.waiting-on :as waiting-on])
  (:import (java.time Instant)))

(def ^:private head "2fba27a0c1d2e3f405162738495a6b7c8d9e0f12")
(def ^:private stale "a667ef3b1c2d3e4f5061728394a5b6c7d8e9f012")

(def ^:private a-change
  {:id "c-1" :kind :change :state :open
   :data {:repository "ckopsa/waymark" :head_sha head :rounds 1
          :behind_base false}})

(defn- a-plan [scene]
  {:id "p-1" :kind :org_plan :state :proposed
   :data {:name "greenleaf" :change "c-1" :first_week_scene_sha scene
          :findings []}})

(defn- ctx [sittings]
  {:read (fn [kind id] (when (and (= :change kind) (= "c-1" (str id))) a-change))
   :find (fn [kind _filter _opts] (if (= :sitting kind) sittings []))})

(defn- film-ready [row sittings]
  ((get-in org-plan/org-plan [:computed :film_ready :fn]) row (ctx sittings)))

(def ^:private by-kind
  (into {} (map (juxt :kind identity)) (main/resources)))

(defn- verdict [row items]
  (:verdict (waiting-on/block org-plan/org-plan [] by-kind row
                              {:now (Instant/now)
                               :data (assoc (:data row) :film_ready items)})))

(deftest a-stale-scene-shows-and-names-scene-at-head
  (let [row (a-plan stale)
        items (film-ready row [])
        by-item (into {} (map (juxt :item identity)) items)]
    (testing "the items come in order"
      (is (= ["scene_at_head" "scene_check" "no_error_findings" "rounds_left"
              "branch_current" "not_held" "state"]
             (mapv :item items))))
    (testing "scene_at_head is not ok, and says why"
      (is (false? (:ok (by-item "scene_at_head"))))
      (is (str/includes? (:says (by-item "scene_at_head")) "not at the head")))
    (testing "the rest are ok"
      (is (every? :ok (rest items))))
    (testing "the verdict of a proposed plan names it"
      (is (str/includes? (verdict row items) "waits on scene_at_head")))
    (testing "a plan in another state is not named"
      (is (not (str/includes? (verdict (assoc row :state :drafting) items)
                              "waits on"))))))

(deftest a-ready-plan-shows-every-item-ok
  (let [row (a-plan head)
        items (film-ready row [])]
    (is (= 7 (count items)))
    (is (every? :ok items) (pr-str (remove :ok items)))
    (is (str/includes? (:says (nth items 3)) "2 of the change's 3 rounds"))
    (is (not (str/includes? (verdict row items) "waits on")))))

(deftest a-held-plan-names-its-sitting
  (let [s {:id "s-9" :state :open
           :data {:seat "director" :walked_rows ["p-1"]
                  :last_call_at "2026-10-22T20:00:00Z"}}
        held (first (filter #(= "not_held" (:item %))
                            (film-ready (a-plan head) [s])))]
    (is (false? (:ok held)))
    (is (str/includes? (:says held) "s-9"))
    (is (str/includes? (:says held) "2026-10-22T20:00:00Z"))))

(deftest with-no-read-it-says-nothing
  (is (nil? ((get-in org-plan/org-plan [:computed :film_ready :fn])
             (a-plan head) {}))))
