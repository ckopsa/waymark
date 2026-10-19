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
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.roles :as roles]
            [waymark10.server.runner-links :as runner-links]
            [waymark10.server.seed :as seed]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]
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

(def ^:private mayor-key
  "The key the owner offers the seeded mayor: base64url, as a machine
  mints it."
  "ZmFjdG9yeS1zZWVkLW1heW9yLWtleS0wMQ")

(defn- sat-as-mayor
  "The owner offers the seeded mayor a key, their connector sits with
  it through the real `waymark_sit`, and the answer is the visibility
  the bound sitter wears — the seat's grant, as every call after the
  sit is judged by it (ticket_birth_test's `sitter-session`)."
  [eng]
  (let [owner (t/principal {:id "tim" :display "Tim"})
        delegate (assoc (t/principal {:id "connector:tim" :type :agent
                                      :display "Claude for Tim"})
                        :acts-for "tim")
        _ (inv/invoke! eng :seat (:id (named eng :seat "mayor")) :offer_key
                       {:key mayor-key} {:principal owner})
        sid (mcp/open-session! eng)
        r (mcp/call-tool eng (mcp/door eng)
                         {:principal delegate :mcp-session-id sid}
                         "waymark_sit" {:key mayor-key})
        text (str (get-in r [:content 0 :text]))
        sitter (:sitter (:bound (mcp/touch-session! eng sid)))]
    (is (false? (:isError r)) text)
    (is (some? (:grant (wire/read-json text))) text)
    (is (some? sitter) "waymark_sit bound the session to the mayor's sitter")
    (grants/worn-visibility eng sitter)))

(def ^:private explained
  "The cracks on the kinds `walked` names that the seed leaves standing,
  each [kind state crack door remedy] with why. The mayor is the
  owner's interactive seat (the template's mode) and holds the grooming
  doors (ticket restate, reopen, show_evidence, resume; change rework),
  so their cracks are gone. No seat holds these: each stays the
  person's by design, and an org's mayor reaches it through a held
  call. A seed change that opens any other crack on a walked kind
  fails here."
  (let [policy "The repository's policy stays the person's by design (policy): no seat writes it; the mayor reaches it through a held call."
        judgment "Promoting a judgment stays the person's by design (policy), as the seed itself does; the mayor reaches it through a held call."
        domain "Restating a domain stays the person's by design (power): no seat holds the navigation; the mayor reaches it through a held call."
        seat "Parking, unparking and retiring a seat stay the person's by design (power and money); the mayor reaches them through a held call."]
    {["approval_request" "offered" "silent_person_exit" nil nil]
     "An ask's offered doors stay the person's by design (power): no seat inbox tells them, the ask door does."
     ["change" "failing" "remedy_unheld" "submit" "repo_policy/create"] policy
     ["change" "failing" "remedy_unheld" "submit" "repo_policy/restate"] policy
     ["change" "open" "remedy_unheld" "submit" "repo_policy/create"] policy
     ["change" "open" "remedy_unheld" "submit" "repo_policy/restate"] policy
     ["change" "submitted" "remedy_unheld" "submit" "repo_policy/create"] policy
     ["change" "submitted" "remedy_unheld" "submit" "repo_policy/restate"] policy
     ["change" "stuck" "cadence_only" nil nil]
     "The mayor reworks a stuck change on its daily sitting; the person reads every stall."
     ["seat" "active" "no_exit" nil nil] seat
     ["seat" "active" "remedy_unheld" "restate" "domain/restate"] domain
     ["seat" "active" "remedy_unheld" "restate" "judgment/promote"] judgment
     ["seat" "parked" "no_exit" nil nil] seat
     ["seat" "parked" "remedy_unheld" "unpark" "judgment/promote"] judgment
     ["seat" nil "remedy_unheld" "create" "domain/restate"] domain
     ["seat" nil "remedy_unheld" "create" "judgment/promote"] judgment
     ["ticket" "deferred" "cadence_only" nil nil]
     "The mayor resumes a deferred ticket on its daily sitting, when the person says so."
     ["verdict" "said" "no_exit" nil nil]
     "A said verdict is the judge's record and stays said; the owner reads it."
     ["verdict" nil "remedy_unheld" "create" "judgment/promote"] judgment}))

(def ^:private unheld-off-the-walk
  "The remedy_unheld cracks on kinds no seeded seat walks, each
  [kind state crack door remedy] with why. They are named so a new one
  fails here rather than hiding in `by-crack`'s count: when ticket
  7215f8fa's grooming doors closed 14 walked cracks, the count fell by
  12, not 14 (ticket bf5c3ed1). Each remedy is a door that stays the
  person's by design."
  (let [judgment "Revising a judgment stays the person's by design (policy), as promoting one does."
        role "Minting a role stays the person's by design (power): no seat writes the roles it is judged by."
        link "Linking a model or a schedule stays the person's by design (money): it names who is paid to sit."
        runner "A runner link carries a credential, and a credential is the person's by design."]
    {["domain" "active" "remedy_unheld" "restate" "seat/park"]
     "Parking a seat stays the person's by design (power and money), as the seat's own cracks say."
     ["judgment" "draft" "remedy_unheld" "promote" "judgment/revise"] judgment
     ["judgment" "draft" "remedy_unheld" "revise" "judgment/revise"] judgment
     ["judgment" nil "remedy_unheld" "create" "judgment/revise"] judgment
     ["member" "active" "remedy_unheld" "assign_roles" "role/create"] role
     ["member" "active" "remedy_unheld" "set_factory_role" "role/create"] role
     ["member" nil "remedy_unheld" "create" "role/create"] role
     ["model" nil "remedy_unheld" "create" "model/link"] link
     ["runner_link" "broken" "remedy_unheld" "restate" "runner_link/restate"] runner
     ["runner_link" "live" "remedy_unheld" "restate" "runner_link/restate"] runner
     ["runner_link" nil "remedy_unheld" "create" "runner_link/create"] runner
     ["schedule" "broken" "remedy_unheld" "link_like" "schedule/link"] link
     ["schedule" "broken" "remedy_unheld" "relink_model" "model/link"] link
     ["schedule" "live" "remedy_unheld" "link_like" "schedule/link"] link
     ["schedule" "live" "remedy_unheld" "relink_model" "model/link"] link
     ["schedule" "paused" "remedy_unheld" "link_like" "schedule/link"] link
     ["schedule" "paused" "remedy_unheld" "relink_model" "model/link"] link
     ["schedule" "pending" "remedy_unheld" "link_like" "schedule/link"] link
     ["schedule" "pending" "remedy_unheld" "relink_model" "model/link"] link
     ["secret" nil "remedy_unheld" "create" "secret/create"]
     "A secret is a credential, and a credential is the person's by design."}))

(def ^:private by-crack
  "The whole cracks report over the seeded engine, by crack. Beside
  the `explained` ones, they stand on the kinds a factory-only engine
  serves and no seeded seat staffs. A change in
  either direction fails here and is read. Member reinstate's hold
  (HC2, ticket bed1f2f6) is declared `:hold :agents`: it holds only an
  agent's call, so a suspended member's exit stays a seat's (9b8e9749)."
  {"cadence_only" 2 "no_exit" 72 "remedy_unheld" 32 "silent_person_exit" 20})

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
      (testing "the remedy_unheld cracks off the walk are the named ones"
        (let [off (into #{}
                        (comp (filter #(= "remedy_unheld" (str (:crack %))))
                              (remove #(contains? kinds (str (:kind %))))
                              (map (juxt :kind :state :crack :door :remedy)))
                        (:cracks report))
              named (set (keys unheld-off-the-walk))]
          (is (= #{} (reduce disj off named)) "a new one, unnamed")
          (is (= #{} (reduce disj named off)) "a named one that fell")))
      (testing "and the rest are pinned by count"
        (is (= by-crack (:by_crack report)))))
    (testing "the seeded mayor's grant acts: the grooming doors are admitted"
      (let [action? (:action? (sat-as-mayor eng))]
        (doseq [[kind action] [["ticket" "restate"] ["ticket" "reopen"]
                               ["ticket" "resume"] ["change" "rework"]]]
          (is (true? (action? kind action)) (str kind "/" action)))
        (testing "and the repository's policy stays the person's"
          (is (false? (action? "repo_policy" "restate"))))))
    (testing "a second load skips it"
      (is (false? (:seeded (seed/load! eng factory {:params params}))))
      (is (= 4 (count (dev/rows eng :seat)))))))
