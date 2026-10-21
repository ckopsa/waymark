(ns factory10.org-plan-carry-test
  "A base move that leaves the org alone carries the take (ticket
  ce8a4e17): the findings pass asks the forge whether org_dir changed
  between the take and the change's new head. A going_live plan whose
  org did not change keeps its take and its yes, and the merge pass
  takes that yes for the new head with no refilm; one whose org changed
  is proposed again, and its finding says so. Under base_follow
  when_org_dir_changes a simulated plan keeps its take the same way.

  No database: a memory engine is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [factory10.sources.forge :as forge]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private head-2 "a667ef3b1c2d3e4f5061728394a5b6c7d8e9f012")

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(defn- an-engine []
  (engine/engine {:storage (memory/storage)
                  :resources (vec (main/resources))}))

(defn- row-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx kind (str id) {})))))

(defn- filed!
  "A plan the owner filed as greenleaf, with `data` beside. → its id."
  [eng data]
  (str (:id (:row (inv/create! eng :org_plan (merge {:name "greenleaf"} data)
                               {:principal the-owner})))))

(defn- simulated!
  "The drafting plan `id` proposed with its change at `walk/a-head`,
  filmed there and flipped live. → the id."
  [eng id]
  (walk/proposed! eng id the-owner)
  (inv/invoke! eng :org_plan id :hand_in_film
               {:film_url "https://example.com/greenleaf.mp4"
                :take_report "{}" :head_sha walk/a-head}
               {:principal the-owner})
  (inv/invoke! eng :org_plan id :attach_film
               {:film_url "https://example.com/greenleaf.mp4"} walk/the-engine)
  (walk/flipped! eng id walk/a-head)
  id)

(defn- a-forge
  "A forge with no artifact that answers `changed?` to every path
  compare, each recorded in `asked` as [from to path]."
  [changed? asked]
  (reify
    forge/ForgeArtifact
    (forge-artifact [_ _ _] nil)
    forge/ForgePathDiff
    (forge-path-changed? [_ _ from to path]
      (swap! asked conj [from to path])
      changed?)))

(defn- pass!
  "One findings pass over `source`, every moved head a base move."
  [eng source]
  (forge/findings-pass! eng source {} (fn [& _] nil)
                        {:base-move? (constantly true)}))

(defn- moved!
  "The change of plan `id` brought forward from base to `head`."
  [eng id head]
  (inv/invoke! eng :change (str (get-in (row-of eng :org_plan id) [:data :change]))
               :observe {:head_sha head} walk/the-engine))

(defn- state [eng id] (name (:state (row-of eng :org_plan id))))

(deftest a-going-live-plan-keeps-its-take-when-the-base-move-left-the-org-alone
  (let [eng (an-engine)
        id (walk/going-live! eng (filed! eng {}) the-owner)
        asked (atom [])
        data #(:data (row-of eng :org_plan id))]
    (moved! eng id head-2)
    (let [census (pass! eng (a-forge false asked))]
      (is (= 1 (:carried census)))
      (is (nil? (:refilmed census))))
    (is (= [[walk/a-head head-2 "orgs/greenleaf"]] @asked)
        "the forge was asked of the org's directory, from the take to the new head")
    (testing "the take, the findings and the yes move to the new head"
      (is (= "going_live" (state eng id)))
      (is (= head-2 (:take_sha (data))))
      (is (= head-2 (:findings_sha (data))))
      (is (= head-2 (:approved_head (data)))))
    (testing "the merge pass takes the owner's yes for the new head: it waits on CI alone"
      (let [change (row-of eng :change (:change (data)))]
        (is (= head-2 (::bench/go-live-yes
                       (first (#'bench/go-live-yeses! eng [change])))))))
    (testing "a second pass leaves it be"
      (is (nil? (:carried (pass! eng (a-forge false asked)))))
      (is (= 1 (count @asked))))))

(deftest a-going-live-plan-whose-org-the-base-move-changed-is-filmed-again
  (let [eng (an-engine)
        id (walk/going-live! eng (filed! eng {}) the-owner)
        data #(:data (row-of eng :org_plan id))]
    (moved! eng id head-2)
    (is (= 1 (:refilmed (pass! eng (a-forge true (atom []))))))
    (is (= "proposed" (state eng id)))
    (is (nil? (:take_sha (data))))
    (is (nil? (:approved_head (data))))
    (is (some #(and (= "take_left" (str (:key %)))
                    (str/includes? (str (:sentence %)) "changed the org")
                    (str/includes? (str (:sentence %)) (subs walk/a-head 0 7)))
              (:findings (data)))
        "the finding says the base move changed the org, so it must be filmed again")))

(deftest a-simulated-plan-under-when-org-dir-changes-keeps-its-take
  (testing "under when_org_dir_changes, a base move that left the org alone keeps the plan simulated"
    (let [eng (an-engine)
          id (simulated! eng (filed! eng {:base_follow "when_org_dir_changes"}))
          data #(:data (row-of eng :org_plan id))]
      (moved! eng id head-2)
      (is (= 1 (:carried (pass! eng (a-forge false (atom []))))))
      (is (= "simulated" (state eng id)))
      (is (= head-2 (:take_sha (data))))
      (is (= head-2 (:findings_sha (data))))))
  (testing "under when_org_dir_changes, a move that changed the org refilms"
    (let [eng (an-engine)
          id (simulated! eng (filed! eng {:base_follow "when_org_dir_changes"}))]
      (moved! eng id head-2)
      (is (= 1 (:refilmed (pass! eng (a-forge true (atom []))))))
      (is (= "proposed" (state eng id)))))
  (testing "under the default, the same move refilms and the forge is not asked"
    (let [eng (an-engine)
          id (simulated! eng (filed! eng {}))
          asked (atom [])]
      (moved! eng id head-2)
      (is (= 1 (:refilmed (pass! eng (a-forge false asked)))))
      (is (= "proposed" (state eng id)))
      (is (empty? @asked)))))

(defn- filming!
  "The drafting plan `id` proposed with its change at `walk/a-head`, and
  a take of that head handed in, waiting to be judged. → the id."
  [eng id]
  (walk/proposed! eng id the-owner)
  (inv/invoke! eng :org_plan id :hand_in_film
               {:film_url "https://example.com/greenleaf.mp4"
                :take_report "{}" :head_sha walk/a-head}
               {:principal the-owner})
  id)

(deftest a-take-waiting-to-be-judged-survives-a-main-only-merge
  (testing "a base move that left the org alone keeps the take, at the new head"
    (let [eng (an-engine)
          id (filming! eng (filed! eng {}))
          asked (atom [])
          data #(:data (row-of eng :org_plan id))]
      (moved! eng id head-2)
      (let [census (pass! eng (a-forge false asked))]
        (is (= 1 (:carried census)))
        (is (nil? (:voided census))))
      (is (= [[walk/a-head head-2 "orgs/greenleaf"]] @asked))
      (is (= "filming" (state eng id)))
      (is (= head-2 (:take_sha (data))))
      (is (= "https://example.com/greenleaf.mp4" (:take_film_url (data))))
      (is (not-any? #(= "take_voided" (str (:key %))) (:findings (data))))
      (testing "and the film pass judges it"
        (is (nil? (:voided (forge/film-pass! eng {} (fn [& _] nil)
                                             (fn [_ _] {:rules []})))))
        (is (not= "proposed" (state eng id))))))
  (testing "an edit under the org's directory voids it"
    (let [eng (an-engine)
          id (filming! eng (filed! eng {}))
          data #(:data (row-of eng :org_plan id))]
      (moved! eng id head-2)
      (is (= 1 (:voided (pass! eng (a-forge true (atom []))))))
      (is (= "proposed" (state eng id)))
      (is (nil? (:take_sha (data))))
      (is (some #(= "take_voided" (str (:key %))) (:findings (data))))))
  (testing "under base_follow always, every move voids it and the forge is not asked"
    (let [eng (an-engine)
          id (filming! eng (filed! eng {:base_follow "always"}))
          asked (atom [])]
      (moved! eng id head-2)
      (is (= 1 (:voided (pass! eng (a-forge false asked)))))
      (is (= "proposed" (state eng id)))
      (is (empty? @asked)))))
