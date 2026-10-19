(ns factory10.org-findings-pass-test
  "The forge pass reads the org-findings artifact (ticket c4893567): a
  plan in drafting and one in proposed each get their findings written
  at their change's new head, through the door their state affords, and
  a head already recorded writes nothing and asks the forge for nothing.
  The artifact read is stubbed.

  No database: a memory engine is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [factory10.sources.forge :as forge]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private repo "ckopsa/waymark")
(def ^:private head-1 "2fba27a0c1d2e3f405162738495a6b7c8d9e0f12")
(def ^:private head-2 "a667ef3b1c2d3e4f5061728394a5b6c7d8e9f012")
(def ^:private head-3 "5e469c6166edf6a3f28ae2e6bdb88b775025f14d")

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(def ^:private the-forge {:principal mirror/source-principal})

(def ^:private gap
  {:key "crack/quote/person_only" :severity "warning" :class "crack"
   :subject "quote" :sentence "No one approves a quote over $5k."
   :remedy "Name who approves a quote over $5k."})

(def ^:private lint
  {:key "lint/quote/unused-binding" :severity "warning" :class "lint"
   :subject "quote" :sentence "The binding row is never read."
   :remedy "Remove the binding."})

(def ^:private red-film
  {:key "film/invoice/late-payer-chased" :severity "error" :class "film"
   :subject "invoice" :sentence "In the film no one chased the late invoice."
   :remedy "Name who chases a late invoice."})

(defn- a-change! [eng number head]
  (str (:id (:row (inv/create! eng :change
                               {:change_id (str "github:" repo "#" number)
                                :repository repo
                                :number number
                                :title "An org's pull request"
                                :head_branch (str "orgs/change-" number)
                                :base_branch "main"
                                :head_sha head}
                               the-forge)))))

(defn- a-proposed-plan!
  "A plan its owner filed, audited clean at its change's first head
  (`0000000`) and put up with `change`, which is then pushed to the head
  it was made with: propose opens only at an audited head (ticket
  db8b35bb)."
  [eng org change]
  (let [st (:storage eng)
        head (get-in (store/with-tx st (fn [tx] (store/load-row st tx :change change {})))
                     [:data :head_sha])
        id (str (:id (:row (inv/create! eng :org_plan {:name org}
                                        {:principal the-owner}))))]
    (inv/invoke! eng :change change :observe {:head_sha "0000000"} the-forge)
    (inv/invoke! eng :org_plan id :record_findings
                 {:head_sha "0000000" :findings []} the-forge)
    (inv/invoke! eng :org_plan id :propose {:change change}
                 {:principal the-owner})
    (inv/invoke! eng :change change :observe {:head_sha head} the-forge)
    id))

(defn- plan [eng id]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/load-row st tx :org_plan id {})))))

(defn- artifacts
  "A source whose one verb is the artifact read, over the atom
  `by-name`, keeping each read in `asked`."
  [by-name asked]
  (reify forge/ForgeArtifact
    (forge-artifact [_ repository artifact-name]
      (swap! asked conj [repository artifact-name])
      (get @by-name artifact-name))))

(deftest the-pass-records-each-new-head-once
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})
        drafting (a-proposed-plan! eng "greenleaf" (a-change! eng 101 head-1))
        acme-change (a-change! eng 102 head-2)
        proposed (a-proposed-plan! eng "acme" acme-change)
        _ (inv/invoke! eng :org_plan drafting :hand_in_film
                       {:film_url "https://films.example.test/greenleaf.mp4"
                        :take_report "{}" :head_sha "fffffff1"} the-forge)
        _ (inv/invoke! eng :org_plan drafting :record_film_findings
                       {:head_sha "fffffff1" :findings [red-film]} the-forge)
        by-name (atom {(str "org-findings-greenleaf-" head-1) {:greenleaf [gap]}
                       (str "org-findings-acme-" head-2) {:acme [gap lint]}})
        asked (atom [])
        source (artifacts by-name asked)
        pass! #(forge/findings-pass! eng source {} (fn [& _] nil))
        keys-of #(mapv :key (get-in (plan eng %) [:data :findings]))
        state #(name (:state (plan eng %)))
        sha #(get-in (plan eng %) [:data :findings_sha])]
    (is (= "drafting" (state drafting)) "the red film drafted it again")
    (is (= "proposed" (state proposed)))
    (testing "a new head's findings land on each plan, by its state's door"
      (is (= 2 (:findings (pass!))))
      (is (= #{[repo (str "org-findings-greenleaf-" head-1)]
               [repo (str "org-findings-acme-" head-2)]}
             (set @asked)))
      (is (= "drafting" (state drafting)))
      (is (= head-1 (sha drafting)))
      (is (= [(:key gap)] (keys-of drafting)))
      (is (= "proposed" (state proposed)))
      (is (= head-2 (sha proposed)))
      (is (= [(:key gap) (:key lint)] (keys-of proposed))))
    (testing "a head already recorded writes nothing and asks for nothing"
      (reset! asked [])
      (is (nil? (:findings (pass!))))
      (is (empty? @asked))
      (is (= [(:key gap) (:key lint)] (keys-of proposed))))
    (testing "an artifact not there yet is tried on the next pass"
      (inv/invoke! eng :change acme-change :observe {:head_sha head-3}
                   the-forge)
      (reset! asked [])
      (is (nil? (:findings (pass!))))
      (is (= [[repo (str "org-findings-acme-" head-3)]] @asked))
      (is (= head-2 (sha proposed)))
      (swap! by-name assoc (str "org-findings-acme-" head-3) {:acme []})
      (is (= 1 (:findings (pass!))))
      (is (= head-3 (sha proposed)))
      (is (= [] (keys-of proposed)))
      (is (= "proposed" (state proposed))))))

(deftest the-pass-writes-the-law-page-beside-the-findings
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})
        change (a-change! eng 102 head-2)
        id (a-proposed-plan! eng "acme" change)
        by-name (atom {(str "org-findings-acme-" head-2)
                       {:acme [gap] :law_page "# The law of acme"}})
        pass! #(forge/findings-pass! eng (artifacts by-name (atom [])) {}
                                     (fn [& _] nil))
        data #(:data (plan eng id))]
    (testing "the pass writes both onto the plan for that head"
      (is (= 1 (:findings (pass!))))
      (is (= head-2 (:findings_sha (data))))
      (is (= [(:key gap)] (mapv :key (:findings (data)))))
      (is (= "# The law of acme" (:law_page (data)))))
    (testing "a later head replaces both"
      (inv/invoke! eng :change change :observe {:head_sha head-3} the-forge)
      (swap! by-name assoc (str "org-findings-acme-" head-3)
             {:acme [lint] :law_page "# The law of acme, again"})
      (is (= 1 (:findings (pass!))))
      (is (= head-3 (:findings_sha (data))))
      (is (= [(:key lint)] (mapv :key (:findings (data)))))
      (is (= "# The law of acme, again" (:law_page (data)))))))

(deftest the-pass-lands-the-first-week-scene-with-its-head
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})
        change (a-change! eng 102 head-2)
        id (a-proposed-plan! eng "acme" change)
        by-name (atom {(str "org-findings-acme-" head-2)
                       {:acme [gap] :first_week_scene "{:scene {:seed \"acme\"}}"}})
        pass! #(forge/findings-pass! eng (artifacts by-name (atom [])) {}
                                     (fn [& _] nil))
        data #(:data (plan eng id))]
    (testing "the pass writes the scene and the head it was rendered at"
      (is (= 1 (:findings (pass!))))
      (is (= "{:scene {:seed \"acme\"}}" (:first_week_scene (data))))
      (is (= head-2 (:first_week_scene_sha (data))))
      (is (= "proposed" (name (:state (plan eng id))))))
    (testing "a later head replaces both"
      (inv/invoke! eng :change change :observe {:head_sha head-3} the-forge)
      (swap! by-name assoc (str "org-findings-acme-" head-3)
             {:acme [] :first_week_scene "{:scene {:seed \"acme\" :day 2}}"})
      (is (= 1 (:findings (pass!))))
      (is (= "{:scene {:seed \"acme\" :day 2}}" (:first_week_scene (data))))
      (is (= head-3 (:first_week_scene_sha (data)))))
    (testing "a head whose artifact carries none keeps the scene of the head it names"
      (inv/invoke! eng :change change :observe {:head_sha head-1} the-forge)
      (swap! by-name assoc (str "org-findings-acme-" head-1) {:acme []})
      (is (= 1 (:findings (pass!))))
      (is (= head-1 (:findings_sha (data))))
      (is (= head-3 (:first_week_scene_sha (data)))))))

(deftest a-source-without-artifacts-records-nothing
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (vec (main/resources))})
        id (a-proposed-plan! eng "greenleaf" (a-change! eng 101 head-1))]
    (is (= {} (forge/findings-pass! eng (reify Object) {} (fn [& _] nil))))
    (is (= "0000000" (get-in (plan eng id) [:data :findings_sha])))))
