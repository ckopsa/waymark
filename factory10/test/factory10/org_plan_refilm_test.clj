(ns factory10.org-plan-refilm-test
  "A plan whose change merged before go_live (ticket 87fc7996): the
  sit's relink names a fresh change on a simulated plan whose change is
  gone, and on no other; a head off the take sends a simulated plan back
  to proposed through the findings pass; go_live refuses a take that is
  not the change's head and, filmed at the head, takes the yes the merge
  pass merges.

  No database: a memory engine is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [factory10.sources.forge :as forge]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private head-2 "a667ef3b1c2d3e4f5061728394a5b6c7d8e9f012")

(def ^:private a-film "https://example.com/greenleaf.mp4")

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(defn- an-engine []
  (engine/engine {:storage (memory/storage)
                  :resources (vec (main/resources))}))

(defn- row-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx kind (str id) {})))))

(defn- refused? [f]
  (try (f) false (catch Exception _ true)))

(defn- filmed!
  "The director's take of `head` on the proposed plan `id`, landed."
  [eng id head]
  (inv/invoke! eng :org_plan id :hand_in_film
               {:film_url a-film :take_report "{}" :head_sha head}
               {:principal the-owner})
  (inv/invoke! eng :org_plan id :attach_film {:film_url a-film} walk/the-engine))

(defn- simulated!
  "A plan the owner filed, proposed with its change at `walk/a-head`,
  filmed there and flipped live. → [id change]."
  [eng]
  (let [id (str (:id (:row (inv/create! eng :org_plan {:name "greenleaf"}
                                        {:principal the-owner}))))
        change (walk/audited! eng id {})]
    (inv/invoke! eng :org_plan id :propose {:change change} {:principal the-owner})
    (filmed! eng id walk/a-head)
    (walk/flipped! eng id walk/a-head)
    [id change]))

(defn- a-fresh-change!
  "The change the sit mints for plan `id` once its first merged: born
  from the plan, on a branch of its own, at `head`. → its id."
  [eng id head]
  (str (:id (:row (inv/create! eng :change
                               {:change_id (str "org_plan:" id ":fresh")
                                :born_from (str "org_plan:" id)
                                :repository "ckopsa/waymark"
                                :title "An org's pull request, again"
                                :head_branch (str "bench/" id "-2")
                                :base_branch "main"
                                :head_sha head}
                               walk/the-engine)))))

(deftest a-simulated-plan-whose-change-merged-names-a-fresh-change
  (let [eng (an-engine)
        [id change] (simulated! eng)
        fresh (a-fresh-change! eng id head-2)
        relink! #(inv/invoke! eng :org_plan id :relink_change {:change fresh} %)
        plan #(row-of eng :org_plan id)]
    (testing "a plan whose change is in play keeps it"
      (is (refused? #(relink! walk/the-engine)))
      (is (= change (str (get-in (plan) [:data :change])))))
    (inv/invoke! eng :change change :merge {} walk/the-engine)
    (testing "only the engine's hand names the fresh change"
      (is (refused? #(relink! {:principal the-owner}))))
    (testing "once its change merged, the sit's relink names the fresh one and the plan stays simulated"
      (relink! walk/the-engine)
      (is (= fresh (str (get-in (plan) [:data :change]))))
      (is (= "simulated" (name (:state (plan))))))))

(deftest a-new-head-is-filmed-again-before-go-live
  (let [eng (an-engine)
        [id change] (simulated! eng)
        by-name (atom {})
        pass! #(forge/findings-pass! eng
                                     (reify forge/ForgeArtifact
                                       (forge-artifact [_ _ artifact-name]
                                         (get @by-name artifact-name)))
                                     {} (fn [& _] nil))
        go-live! #(inv/invoke! eng :org_plan id :go_live {} {:principal the-owner})
        data #(:data (row-of eng :org_plan id))
        state #(name (:state (row-of eng :org_plan id)))]
    (testing "a take of the change's head stays simulated"
      (is (nil? (:refilmed (pass!))))
      (is (= "simulated" (state))))
    (inv/invoke! eng :change change :refresh_head {:head_sha head-2} walk/the-pull-request)
    (testing "go_live refuses a take that is not the change's head"
      (is (refused? go-live!))
      (is (= "simulated" (state))))
    (testing "the findings pass sends the plan back to proposed, naming the old take"
      (is (= 1 (:refilmed (pass!))))
      (is (= "proposed" (state)))
      (is (nil? (:take_sha (data))))
      (is (some #(and (= "take_left" (str (:key %)))
                      (str/includes? (str (:sentence %)) (subs walk/a-head 0 7)))
                (:findings (data)))))
    (testing "filmed at the new head, go_live takes the yes for that head"
      (swap! by-name assoc (str "org-findings-greenleaf-" head-2)
             {:greenleaf [] :live_flag true :mayor walk/a-mayor})
      (is (= 1 (:findings (pass!))))
      (filmed! eng id head-2)
      (is (= "simulated" (state)))
      (go-live!)
      (is (= "going_live" (state)))
      (is (= head-2 (:approved_head (data)))))))
