(ns factory10.org-plan-refresh-test
  "The mayor's refresh (ticket b7e4f77f): a proposed plan's branch is
  brought up to its base once through the rig's `update_branch`, and
  the plan stays proposed; a conflicting base refuses, naming the paths.

  No database: a memory engine is the whole world, and the rig is a
  fake behind `:bench-rpc`.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private the-mayor
  (t/principal {:id "colton" :type :human :display "colton"}))

(defn- an-engine
  "A memory engine whose rig answers every call with `answer`, and
  keeps each call's name and arguments in `calls`."
  [answer calls]
  (engine/engine {:storage (memory/storage)
                  :resources (vec (main/resources))
                  :services {:bench-rpc (fn [_method params]
                                          (swap! calls conj params)
                                          {:structuredContent {:result answer}})}}))

(defn- row-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx kind (str id) {})))))

(defn- refusal-of [f]
  (try (f) nil (catch Exception e (str (ex-message e) " " (pr-str (ex-data e))))))

(defn- proposed-plan!
  "A plan the mayor filed and proposed with its change at `walk/a-head`.
  → [id change]."
  [eng]
  (let [id (str (:id (:row (inv/create! eng :org_plan {:name "greenleaf"}
                                        {:principal the-mayor}))))
        change (walk/audited! eng id {})]
    (inv/invoke! eng :org_plan id :propose {:change change} {:principal the-mayor})
    [id change]))

(defn- update-calls [calls]
  (filterv #(= "bench__update_branch" (:name %)) @calls))

(deftest refresh-merges-the-base-into-a-proposed-plans-branch
  (let [calls (atom [])
        eng (an-engine {:state "updated"} calls)
        [id _change] (proposed-plan! eng)]
    (inv/invoke! eng :org_plan id :refresh {} {:principal the-mayor})
    (testing "the rig is asked once to bring the plan's branch up, at its head"
      (let [asked (update-calls calls)]
        (is (= 1 (count asked)))
        (is (= walk/a-head (str (get-in (first asked) [:arguments :head_sha]))))
        (is (= "ckopsa/waymark" (str (get-in (first asked) [:arguments :repo]))))))
    (testing "the plan stays proposed"
      (is (= "proposed" (name (:state (row-of eng :org_plan id))))))))

(deftest a-conflicting-base-refuses-naming-the-paths
  (let [calls (atom [])
        eng (an-engine {:refused "not_mergeable"
                        :reason "merge conflict"
                        :conflicts ["orgs/greenleaf/org.edn" "factory10/src/x.clj"]}
                       calls)
        [id _change] (proposed-plan! eng)
        before (row-of eng :org_plan id)
        why (refusal-of #(inv/invoke! eng :org_plan id :refresh {} {:principal the-mayor}))]
    (testing "the door refuses, naming each conflicting path"
      (is (some? why))
      (is (str/includes? (str why) "orgs/greenleaf/org.edn"))
      (is (str/includes? (str why) "factory10/src/x.clj")))
    (testing "the plan is left as it was"
      (let [after (row-of eng :org_plan id)]
        (is (= "proposed" (name (:state after))))
        (is (= (:data before) (:data after)))))))

(deftest refresh-is-a-proposed-plans-door
  (let [calls (atom [])
        eng (an-engine {:state "updated"} calls)
        id (str (:id (:row (inv/create! eng :org_plan {:name "greenleaf"}
                                        {:principal the-mayor}))))]
    (is (some? (refusal-of #(inv/invoke! eng :org_plan id :refresh {}
                                         {:principal the-mayor}))))
    (is (empty? (update-calls calls)))))
