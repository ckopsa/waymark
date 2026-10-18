(ns workqueue10.factory-seed-test
  "The `factory` seed (epic 00680418, T5): on a fresh factory-only
  engine, booted as `start!` boots one, the seed makes the owner and
  the agent acting for them, both starter judgments promoted, the
  bench, the repository's policy, the pull link and the model, and the
  four seats active and held for that model; a second load skips it.
  No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.factory-seed-test"
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.roles :as roles]
            [waymark10.server.runner-links :as runner-links]
            [waymark10.server.seed :as seed]
            [waymark10.server.store.memory :as memory]
            [workqueue10.main :as main]))

(def ^:private params
  {:owner "tim"
   :repo "tim/widgets"
   ;; nothing listens here: the bench row is born dark
   :bench_url "http://127.0.0.1:9/mcp"
   :model "claude-opus-5"
   :pull_token "a-pull-token-0123456789"})

(defn- booted
  "A factory-only engine with the boot rows the seed finds: the
  providers, the factory roles and domain `factory`."
  []
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (main/factory-only-resources)
                            :suppress-mirror-refresh true})]
    (runner-links/ensure-providers! eng)
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    eng))

(defn- state-of [row] (name (:state row)))

(defn- by-id [eng kind id]
  (first (filter #(= (str id) (str (:id %))) (dev/rows eng kind))))

(defn- named [eng kind n]
  (first (filter #(= n (get-in % [:data :name])) (dev/rows eng kind))))

(defn- walked
  "The kinds the seats walk, or write through a scope entry that
  names an action."
  [seats]
  (into #{}
        (concat (keep #(some-> (get-in % [:data :walk]) str not-empty) seats)
                (for [s seats
                      entry (get-in s [:data :scope])
                      :when (seq (:actions entry))]
                  (str (:kind entry))))))

(def ^:private explained
  "The cracks on the kinds `walked` names that the seed leaves standing,
  each [kind state crack door remedy] with why. The mayor is the
  owner's interactive seat (the template's mode), but no seat holds
  these doors: each is a person's act, and the owner takes it as
  themself, on their own grant, not through a seat. A seed change that
  opens any other crack on a walked kind fails here."
  (let [policy "The repository's policy is the owner's to write: no seat writes it."
        judgment "A judgment is promoted by the owner, as the seed itself does."
        domain "A domain is restated by the owner: no seat holds the navigation."
        seat "Parking, unparking and retiring a seat are the owner's acts."
        reopen "Reopening a done ticket is the owner's call."
        restate "Restating a ticket's ask is the owner's: the mayor grooms and files, it does not rewrite."
        evidence "Showing a ticket's evidence is the owner's act."]
    {["approval_request" "offered" "silent_person_exit" nil nil]
     "An ask waits on the owner's tap; no seat inbox tells them, the ask door does."
     ["change" "failing" "remedy_unheld" "submit" "repo_policy/create"] policy
     ["change" "failing" "remedy_unheld" "submit" "repo_policy/restate"] policy
     ["change" "open" "remedy_unheld" "submit" "repo_policy/create"] policy
     ["change" "open" "remedy_unheld" "submit" "repo_policy/restate"] policy
     ["change" "submitted" "remedy_unheld" "submit" "repo_policy/create"] policy
     ["change" "submitted" "remedy_unheld" "submit" "repo_policy/restate"] policy
     ["change" "stuck" "no_exit" nil nil]
     "A stuck change is reworked or closed by the owner, who reads every stall."
     ["seat" "active" "no_exit" nil nil] seat
     ["seat" "active" "remedy_unheld" "restate" "domain/restate"] domain
     ["seat" "active" "remedy_unheld" "restate" "judgment/promote"] judgment
     ["seat" "parked" "no_exit" nil nil] seat
     ["seat" "parked" "remedy_unheld" "unpark" "judgment/promote"] judgment
     ["seat" nil "remedy_unheld" "create" "domain/restate"] domain
     ["seat" nil "remedy_unheld" "create" "judgment/promote"] judgment
     ["ticket" "blocked" "remedy_unheld" "block" "ticket/reopen"] reopen
     ["ticket" "blocked" "remedy_unheld" "reparent_blocked" "ticket/reopen"] reopen
     ["ticket" "deferred" "no_exit" nil nil]
     "A deferred ticket is resumed by the owner, who deferred it."
     ["ticket" "draft" "remedy_unheld" "block" "ticket/reopen"] reopen
     ["ticket" "draft" "remedy_unheld" "complete" "ticket/restate"] restate
     ["ticket" "draft" "remedy_unheld" "complete" "ticket/show_evidence"] evidence
     ["ticket" "draft" "remedy_unheld" "groom" "ticket/restate"] restate
     ["ticket" "draft" "remedy_unheld" "reparent_draft" "ticket/reopen"] reopen
     ["ticket" "open" "remedy_unheld" "block" "ticket/reopen"] reopen
     ["ticket" "open" "remedy_unheld" "complete" "ticket/restate"] restate
     ["ticket" "open" "remedy_unheld" "complete" "ticket/show_evidence"] evidence
     ["ticket" "open" "remedy_unheld" "reparent" "ticket/reopen"] reopen
     ["ticket" nil "remedy_unheld" "create" "ticket/reopen"] reopen
     ["verdict" "said" "no_exit" nil nil]
     "A said verdict is the judge's record and stays said; the owner reads it."
     ["verdict" nil "remedy_unheld" "create" "judgment/promote"] judgment}))

(def ^:private by-crack
  "The whole cracks report over the seeded engine, by crack. Beside
  the `explained` ones, they stand on the kinds a factory-only engine
  serves and no seeded seat staffs. A change in
  either direction fails here and is read."
  {"no_exit" 74 "remedy_unheld" 44 "silent_person_exit" 20})

(deftest the-factory-seed-stands-a-factory-up-once
  (let [eng (booted)
        factory (seed/read-seed "factory")
        out (seed/load! eng factory {:params params})
        model-id (str (get-in out [:refs :model :id]))
        domain-id (str (:id (domains/domain-named eng "factory")))]
    (is (true? (:seeded out)) (pr-str out))
    (testing "the owner, and the agent acting for them"
      (is (some? (by-id eng :member "tim")))
      (is (= "tim" (get-in (by-id eng :member "claude") [:data :acts_for]))))
    (testing "both judgments are promoted"
      (is (= ["promoted" "promoted"] (mapv state-of (dev/rows eng :judgment)))))
    (testing "the bench, the policy, the pull link and the model"
      (is (= "http://127.0.0.1:9/mcp" (get-in (named eng :mcp_server "bench") [:data :url])))
      (let [policy (first (dev/rows eng :repo_policy))]
        (is (= "tim/widgets" (get-in policy [:data :repository])))
        (is (= ["tests"] (get-in policy [:data :required_checks])))
        (is (= "tests.yml" (get-in policy [:data :test :workflow])))
        (is (= "only" (get-in policy [:data :test :input]))))
      (let [link (by-id eng :runner_link (get-in out [:refs :pull :id]))]
        (is (= "pull" (get-in link [:data :delivery])))
        (is (= [(str (:id link))]
               (get-in (by-id eng :model model-id) [:data :runners])))))
    (testing "the four seats are active and held for the model"
      (doseq [n ["mayor" "code-seat" "draft-judge" "sitting-judge"]
              :let [s (named eng :seat n)]]
        (is (= "active" (some-> s state-of)) n)
        (is (= [model-id] (mapv str (get-in s [:data :held_for]))) n))
      (doseq [n ["mayor" "draft-judge" "sitting-judge"]]
        (is (= domain-id (str (get-in (named eng :seat n) [:data :domain]))) n)))
    (testing "the cracks door answers"
      (let [resp ((engine/handler eng)
                  {:request-method :get :uri "/api/-/cracks"
                   :headers {"x-waymark-principal" "tim"
                             "x-waymark-actor-type" "human"}})]
        (is (= 200 (:status resp)))))
    (let [seats (dev/rows eng :seat)
          kinds (walked seats)
          report (cracks/report eng (constantly true) {})]
      (testing "the seeded seats leave no crack on the kinds they walk or write"
        (let [found (into []
                          (comp (filter #(contains? kinds (str (:kind %))))
                                (map (juxt :kind :state :crack :door :remedy)))
                          (:cracks report))]
          (is (= [] (sort-by pr-str (remove #(contains? explained %) found)))
              (pr-str (sort kinds)))
          (testing "and every explained one still stands"
            (is (= #{} (reduce disj (set (keys explained)) found))))))
      (testing "and the rest are pinned by count"
        (is (= by-crack (:by_crack report)))))
    (testing "a second load skips it"
      (is (false? (:seeded (seed/load! eng factory {:params params}))))
      (is (= 4 (count (dev/rows eng :seat)))))))
