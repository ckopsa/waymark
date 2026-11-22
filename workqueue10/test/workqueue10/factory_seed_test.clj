(ns workqueue10.factory-seed-test
  "The `factory` seed (epic 00680418, T5): on a fresh factory-only
  engine, booted as `start!` boots one, the seed makes the owner and
  the agent acting for them, both starter judgments promoted, the
  bench, the repository's policy, the pull link and the model, and the
  four seats active and held for that model, each fired one with a
  schedule on the pull link an owner's fire goes out through (ticket
  39815531); a second load skips it.
  No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.factory-seed-test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.pull-fires :as pf]
            [waymark10.server.roles :as roles]
            [waymark10.server.runner-links :as runner-links]
            [waymark10.server.schedules :as sch]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.watches :as watches]
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
        seat "Parking, unparking and retiring a seat stay the person's by design (power and money); the mayor reaches them through a held call."
        server "A server's doors are the mayor's or the person's (ticket 8c334f19): the mayor creates, restates, retires and restores one, but no seat inbox tells them, the server's page does."
        model "Repricing and retiring a model stay the person's by design (money): the mayor creates one, and what it costs is the person's to change after."
        plan "An org plan leaves drafting, proposed and live only by its owner's taps or its onboarding seat's, by design (power); that seat is born when a plan's onboarder is seated, so the seed itself stands none on these states."]
    {["approval_request" "offered" "silent_person_exit" nil nil]
     "An ask's offered doors stay the person's by design (power): no seat inbox tells them, the ask door does."
     ["change" "failing" "remedy_unheld" "submit" "repo_policy/create"] policy
     ["change" "failing" "remedy_unheld" "submit" "repo_policy/restate"] policy
     ["change" "open" "remedy_unheld" "submit" "repo_policy/create"] policy
     ["change" "open" "remedy_unheld" "submit" "repo_policy/restate"] policy
     ["change" "submitted" "remedy_unheld" "submit" "repo_policy/create"] policy
     ["change" "submitted" "remedy_unheld" "submit" "repo_policy/restate"] policy
     ["change" "stuck" "person_only" nil nil]
     "The mayor reworks a stuck change when the person sits it: the seed gives it no schedule, so no cadence wakes it; the person reads every stall."
     ["seat" "active" "no_exit" nil nil] seat
     ["seat" "active" "remedy_unheld" "fire" "domain/restate"] domain
     ["seat" "active" "remedy_unheld" "restate" "domain/restate"] domain
     ["seat" "active" "remedy_unheld" "restate" "judgment/promote"] judgment
     ["held_call" "held" "silent_person_exit" nil nil]
     "A held call waits on a person's tap by design (power): that is what holding it means, and the approval door tells them, not a seat inbox."
     ["mcp_server" "dark" "silent_person_exit" nil nil] server
     ["mcp_server" "live" "silent_person_exit" nil nil] server
     ["mcp_server" "retired" "silent_person_exit" nil nil] server
     ["model" "active" "silent_person_exit" nil nil] model
     ["model" "retired" "silent_person_exit" nil nil] model
     ["org_plan" "drafting" "no_exit" nil nil] plan
     ["org_plan" "proposed" "no_exit" nil nil] plan
     ["org_plan" "live" "no_exit" nil nil] plan
     ["plan_part" "dropped" "no_exit" nil nil]
     "A dropped part's reopen is the plan owner's tap by design (power): the mayor may drop a part, and bringing it back is the person's call."
     ["seat" "parked" "person_only" nil nil] seat
     ["seat" "parked" "remedy_unheld" "fire" "domain/restate"] domain
     ["seat" "parked" "remedy_unheld" "unpark" "judgment/promote"] judgment
     ["seat" nil "remedy_unheld" "create" "domain/restate"] domain
     ["seat" nil "remedy_unheld" "create" "judgment/promote"] judgment
     ["ticket" "deferred" "person_only" nil nil]
     "The mayor resumes a deferred ticket when the person sits it and says so: the seed gives it no schedule."
     ["ticket" "draft" "remedy_unheld" "groom" "secret/replace"]
     "The GitHub card's remedy stays the person's by design (power): a secret's value is entered by a person, and no agent writes it (ticket 71cda4a0)."
     ["ticket" "draft" "remedy_unheld" "groom" "repo_policy/mark_enrolled"]
     "The enrolment card's remedy is the engine's: the bench's retry pass stamps it once the token is entered, and no seat takes it (ticket 71cda4a0)."
     ["verdict" "said" "no_exit" nil nil]
     "A said verdict is the judge's record and stays said; the owner reads it."
     ["watch" "active" "no_exit" nil nil]
     "A member drops their own watch by design (privacy): the mayor creates watches, and no seat drops a person's."
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
        runner "A runner link carries a credential, and a credential is the person's by design."
        ops "The base_red guard's remedies, classifying a red run as this change's or as infra, are the ops seat's, which the factory seed does not stand up (ticket 65456c9f)."]
    {["ci_run" "red" "remedy_unheld" "classify_base_red" "ci_run/classify_infra"] ops
     ["ci_run" "red" "remedy_unheld" "classify_base_red" "ci_run/classify_this_change"] ops
     ["domain" "active" "remedy_unheld" "restate" "seat/park"]
     "Parking a seat stays the person's by design (power and money), as the seat's own cracks say."
     ["judgment" "draft" "remedy_unheld" "promote" "judgment/revise"] judgment
     ["judgment" "draft" "remedy_unheld" "revise" "judgment/revise"] judgment
     ["judgment" nil "remedy_unheld" "create" "judgment/revise"] judgment
     ["member" "active" "remedy_unheld" "assign_roles" "role/create"] role
     ["member" "active" "remedy_unheld" "set_factory_role" "role/create"] role
     ["member" nil "remedy_unheld" "create" "role/create"] role
     ["runner_link" "broken" "remedy_unheld" "restate" "runner_link/restate"] runner
     ["runner_link" "live" "remedy_unheld" "restate" "runner_link/restate"] runner
     ["runner_link" nil "remedy_unheld" "create" "runner_link/create"] runner
     ["schedule" "broken" "remedy_unheld" "mend" "runner_link/restate"] runner
     ["schedule" "broken" "remedy_unheld" "mend" "runner_link/restore"] runner
     ["schedule" "held" "remedy_unheld" "mend" "runner_link/restate"] runner
     ["schedule" "held" "remedy_unheld" "mend" "runner_link/restore"] runner
     ["schedule" "pending" "remedy_unheld" "mend" "runner_link/restate"] runner
     ["schedule" "pending" "remedy_unheld" "mend" "runner_link/restore"] runner
     ["secret" nil "remedy_unheld" "create" "secret/create"]
     "A secret is a credential, and a credential is the person's by design."}))

(def ^:private by-crack
  "The whole cracks report over the seeded engine, by crack. Beside
  the `explained` ones, they stand on the kinds a factory-only engine
  serves and no seeded seat staffs. A change in
  either direction fails here and is read. Member reinstate's hold
  (HC2, ticket bed1f2f6) is declared `:hold :agents`: it holds only an
  agent's call, so a suspended member's exit stays a seat's (9b8e9749).
  integration_event's `received` is the applier's alone, and its
  `refused`, `unmatched` and `ambiguous` leave only by a person's retry
  or dismiss, which no seeded seat holds (docs/spec-integrations.md § 3,
  ab9a557c). effect_call's `pending` and `held` are left only by the
  elected runner's own endings (§ 4, § 12 step 6), which no seat
  holds, so neither has a seat's exit (ticket 30487334). team's `active`
  and `retired` are the owner's or a team-admin's (docs/spec-teams.md
  §2), which no seeded seat holds. goal (opted in by be34a944) adds one
  `no_exit`: its restate and retire are the owner's, which no seeded
  seat holds. The fire door's domain wall adds two `remedy_unheld`: its
  remedy, restating the domain's budget, is the owner's. A seeded seat
  now writes its managed door subscriptions with its own create
  (ticket 6ece973a), but a door subscription fires a seat and tells no
  one (E-4.3, ticket 6e595ac4), so that silent_person_exit off the walk
  stands again and the count is 21. The engine's schedule `mend` adds
  two `remedy_unheld`: its remedies, restating or restoring a runner
  link, are the person's (ticket 9daf9b4d), and two more since it
  mends a pending row too (ticket 5e0d33bb). A schedule the provider
  paused is `held` (ticket f1918540): its doors are a broken row's, so
  it adds four `remedy_unheld` and one `no_exit`, since the next
  answered fire, the engine's, is what mends it. ci_run's base_red
  guard adds two `remedy_unheld`: its remedies are the ops seat's,
  which the factory seed does not stand up (ticket 65456c9f). promise
  (opted in by ticket 7520b522) adds four `no_exit`. amendment (opted in
  by ticket 32245fc3) adds two `no_exit`: no seeded seat holds its doors.
  Its `adopt` (ticket 372a3edf) adds two more: adopt is the owner's tap
  and reaches `trial`, whose `settle` is the engine's, so again no seeded
  seat holds them. amendment_policy (ticket 227e1272) adds two `no_exit`:
  its `arm` and `disarm` are the owner's taps. The groom's setup cards
  (ticket 71cda4a0) add two `remedy_unheld`, both `explained`.
  measure_reading (served by ticket 5a946355) adds one `no_exit`: a
  reading is a record, kept and not moved on. plan_part (ticket
  7fc1415a) adds one `no_exit`: a dropped part's reopen is the plan
  owner's tap, and no seeded seat holds it. org_plan's propose (ticket
  f9252928) adds two `remedy_unheld`, both named off the walk. plan_part's
  built-in home guard (ticket 11042960) adds six, named off the walk.
  Film leaving core (ticket 72645741) takes five `no_exit` away: scene,
  take and film_rule are no longer served, so their unheld states go.
  The watch (ticket 14cbd2f4) adds one `no_exit`: a member drops their
  own watch, and no seeded seat holds that door. The mayor's onboarding
  scope (ticket 71a63265) moves the pin on the record: holding plan_part
  create, restate, agree and drop closes the eight remedy_unheld named
  off the walk before, and seat fire turns a parked seat's no_exit into
  person_only; org_plan, plan_part, watch, model, mcp_server and
  held_call are walked now, so their cracks are `explained` above. The
  mayor's mcp_server restore (ticket 8c334f19) closes notifier's two
  remedy_unheld named off the walk."
  {"no_exit" 94 "person_only" 3 "remedy_unheld" 35 "silent_person_exit" 20})

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
        (is (= "house" (get-in policy [:data :merge_by])))
        (is (= "tests.yml" (get-in policy [:data :test :workflow])))
        (is (= "only" (get-in policy [:data :test :input]))))
      (let [link (by-id eng :runner_link (get-in out [:refs :pull :id]))]
        (is (= "pull" (get-in link [:data :delivery])))
        (is (some #(= ["model" model-id] [(:kind %) (str (:id %))]) (get-in link [:data :pools])))))
    (testing "the four seats are active and held for the model"
      (doseq [n ["mayor" "code-seat" "draft-judge" "sitting-judge"]
              :let [s (named eng :seat n)]]
        (is (= "active" (some-> s state-of)) n)
        (is (= [model-id] (mapv str (get-in s [:data :held_for]))) n))
      (doseq [n ["mayor" "draft-judge" "sitting-judge"]]
        (is (= domain-id (str (get-in (named eng :seat n) [:data :domain]))) n)))
    (testing "each fired seat has a schedule on the pull link, and the interactive mayor none"
      (let [pull (str (get-in out [:refs :pull :id]))]
        (doseq [n ["code-seat" "draft-judge" "sitting-judge"]
                :let [s (named eng :seat n)
                      row (sch/schedule-for-seat eng (:id s))]]
          (is (some? row) n)
          (is (= [pull] (sch/pool-of eng row s)) n))
        (is (nil? (sch/schedule-for-seat eng (:id (named eng :seat "mayor")))))))
    (testing "the GitHub secret is made empty, for the owner to fill"
      (let [s (named eng :secret "github")]
        (is (some? s))
        (is (nil? (get-in s [:data :value_set_at])))))
    (testing "no first ticket and no setup quest (ticket 369c8194)"
      (is (empty? (dev/rows eng :ticket)))
      (is (empty? (dev/rows eng :quest))))
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
    (testing "the owner holds approver, so a held power call can be allowed"
      (is (some #{"approver"} (map str (get-in (by-id eng :member "tim") [:data :roles])))))
    (testing "the bench carries the forge's writes, each held for a person, and the repo token"
      (let [bench (named eng :mcp_server "bench")
            powers (into {} (map (juxt :power identity)) (get-in bench [:data :powers]))]
        (doseq [p ["bench.secret" "bench.merge" "bench.dispatch" "bench.repo_create"]]
          (is (= "person" (str (get-in powers [p :approval]))) p))
        (is (contains? powers "bench.secret_list"))
        (is (contains? powers "bench.run_status"))
        (is (= "X-Bench-Repo-Token" (get-in bench [:data :headers 0 :name])))
        (is (some? (named eng :secret "bench-repo")))))
    (testing "the seeded mayor's grant admits onboarding"
      (let [action? (:action? (sat-as-mayor eng))]
        (doseq [[kind action] [["org_plan" "create"] ["plan_part" "agree"]
                               ["seat" "create"] ["seat" "offer_key"]
                               ["held_call" "propose"]]]
          (is (true? (action? kind action)) (str kind "/" action)))
        (testing "and seats' budgets, the runner and the credentials stay the person's"
          (doseq [[kind action] [["seat" "park"] ["seat" "set_budget"]
                                 ["runner_link" "create"] ["secret" "create"]]]
            (is (false? (action? kind action)) (str kind "/" action))))))
    (testing "no mail server without a mail_url"
      (is (nil? (named eng :mcp_server "mail"))))
    (testing "a second load skips it"
      (is (false? (:seeded (seed/load! eng factory {:params params}))))
      (is (= 3 (count (dev/rows eng :schedule))) "and makes no second schedule")
      (is (= 4 (count (dev/rows eng :seat)))))))

(deftest an-owners-fire-on-draft-judge-is-handed-out-on-the-pull-link
  ;; ticket 39815531: the self-host walk's step 7 found no schedule for
  ;; draft-judge, so the owner's fire went nowhere
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed "factory") {:params params})
        pull (str (get-in out [:refs :pull :id]))
        judge (named eng :seat "draft-judge")
        drain! #(consumers/drain-consumer! eng :factory-seed-fire (sch/consumer-fn eng))]
    ;; the cursor seeds at the newest transition, so the drain after
    ;; the fire hears the fire alone
    (drain!)
    (runner-links/seen! eng pull (java.time.Instant/now))
    (inv/invoke! eng :seat (str (:id judge)) :fire {:text "Groom the draft."}
                 {:principal (t/principal {:id "tim" :display "Tim"})
                  :idempotency-key (str "factory-seed-test:" (random-uuid))})
    ;; the pull-fire queue is a Postgres table the in-memory twin keeps
    ;; no copy of, so the queue's own seam records what was handed out
    (let [queued (atom [])]
      (with-redefs [pf/sweep! (constantly nil)
                    pf/pending (constantly [])
                    pf/enqueue! (fn [_ fire] (swap! queued conj fire) "fire-1")]
        (drain!))
      (is (= [[pull "schedule" (str (:id (sch/schedule-for-seat eng (:id judge))))]]
             (mapv (juxt :link-id #(some-> (:for-kind %) name) #(some-> (:for-id %) str))
                   @queued))
          "one fire is handed out on the pull link, for draft-judge's schedule"))))

(def ^:private claim-seed @#'main/claim-seed)

(deftest a-claim-runs-the-factory-seed-for-the-claimer
  ;; ticket 089215fb: nothing passed the claim to the seed, and the pull
  ;; token rides a file in the keys volume, never the params
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "waymark-keys" (make-array java.nio.file.attribute.FileAttribute 0)))
        token-file (java.io.File. ^String dir "pull.token")
        _ (spit token-file "a-file-pull-token-0123456789\n")
        text (wire/write-json (-> params
                                  (dissoc :pull_token)
                                  (assoc :owner "owner" :pull_token_file (str token-file))))
        eng (atom nil)
        printed (with-out-str
                  (reset! eng (engine/engine {:storage (memory/storage)
                                              :resources (main/factory-only-resources)
                                              :suppress-mirror-refresh true
                                              :keys-dir dir
                                              :on-claim (claim-seed "factory" text)})))
        eng @eng
        _ (runner-links/ensure-providers! eng)
        _ (roles/ensure-factory-roles! eng)
        _ (domains/ensure-factory! eng)
        code (second (re-find #"claim code: ([0-9a-f]+)" printed))
        h (engine/handler eng)
        resp (h {:request-method :post :uri "/claim"
                 :headers {"content-type" "application/json"}
                 :body (java.io.ByteArrayInputStream.
                        (.getBytes ^String (wire/write-json {:code code :member "ada" :repo "ada/gizmos"}) "UTF-8"))})]
    (is (some? (:claim eng)) "the engine booted unclaimed")
    (is (= 200 (:status resp)) (pr-str resp))
    (testing "the seed's rows are the claimer's"
      (is (some? (by-id eng :member "ada")))
      (is (= "ada" (get-in (by-id eng :member "claude") [:data :acts_for])))
      (is (nil? (by-id eng :member "owner")) "the params' placeholder owner is not made")
      (is (= 4 (count (dev/rows eng :seat)))))
    (testing "the seed's repository is the claim's (ticket 307a2db7)"
      (is (= "ada/gizmos" (get-in (first (dev/rows eng :repo_policy)) [:data :repository]))
          "the claim's repo, not the params' tim/widgets"))
    (testing "the pull link's token is the file's"
      (let [id (:id (first (filter #(= "pull" (get-in % [:data :delivery]))
                                   (dev/rows eng :runner_link))))
            st (:storage eng)
            row (store/with-tx st (fn [tx] (store/load-row st tx :runner_link (str id) {})))]
        (is (= "a-file-pull-token-0123456789" (get-in row [:data :fire_token])))))
    (testing "the marker keeps a second load from adding anything"
      (is (false? (:seeded (seed/load! eng (seed/read-seed "factory")
                                       {:params (assoc params :owner "ada")})))))))

(deftest a-tickets-groom-waits-on-each-setup-row
  ;; ticket 71cda4a0 (first-run R-6.1, R-6.2): each setup row refuses the
  ;; groom with its card's sentence and names its row, in order. The
  ;; seed files no first ticket (ticket 369c8194), so the owner files one.
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed "factory") {:params params})
        tim (t/principal {:id "tim" :display "Tim" :type :human})
        ticket (str (:id (:row (inv/create! eng :ticket
                                            {:title "Add the waymark repo kit"
                                             :type "feature" :priority 1
                                             :repo "tim/widgets"
                                             :detail "Add the two files of the waymark repo kit."}
                                            {:principal tim}))))
        secret (str (:id (named eng :secret "github")))
        policy (str (:id (first (dev/rows eng :repo_policy))))
        pull (str (get-in out [:refs :pull :id]))
        groom! #(try (inv/invoke! eng :ticket ticket :groom {} {:principal tim})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))
        refused-by (fn [r] (some-> (:guard r) name))
        bound (fn [r] (mapv (juxt #(some-> (:door %) str) #(some-> (:id %) str))
                            (:resolved-remedies r)))
        says? (fn [r card] (some-> (:detail r) str (.startsWith ^String card)))]
    (testing "the GitHub card first, its remedy the secret's replace"
      (let [r (groom!)]
        (is (= "the-github-token-is-entered" (refused-by r)) (pr-str r))
        (is (says? r "Paste your GitHub token: a fine-grained token on the repository,"))
        (is (= [secret] (mapv second (bound r))))
        (is (some-> ^String (ffirst (bound r)) (.endsWith "replace")))))
    (inv/invoke! eng :secret secret :replace {:value "github_pat_0123456789"}
                 {:principal tim
                  :if-match (inv/etag :secret secret (:version (named eng :secret "github")))})
    (testing "then the policy's enrolment, its remedy the policy's mark_enrolled"
      (let [r (groom!)]
        (is (= "the-bench-holds-the-repository" (refused-by r)) (pr-str r))
        (is (says? r "The bench does not hold this repository yet."))
        (is (= [policy] (mapv second (bound r))))
        (is (some-> ^String (ffirst (bound r)) (.endsWith "mark_enrolled")))))
    (inv/invoke! eng :repo_policy policy :mark_enrolled {}
                 {:principal (t/principal {:id "factory10-bench" :type :system})})
    (testing "then the pull link, which names its row and no door"
      (let [r (groom!)]
        (is (= "a-localfire-has-pulled" (refused-by r)) (pr-str r))
        (is (says? r "Open localfire on 127.0.0.1:8112 and give it your Claude token"))
        (is (= (str "runner_link:" pull) (some-> (get-in r [:evidence :link]) str)))
        (is (empty? (:remedies r)))))
    (runner-links/seen! eng pull (java.time.Instant/now))
    (testing "and the groom passes once all three hold"
      (is (nil? (groom!)))
      (is (= "open" (state-of (by-id eng :ticket ticket)))))))

;; ── the setup cards (ticket 369c8194, first-run R-5.2, R-7.1) ──────────

(def ^:private owner (t/principal {:id "tim" :display "Tim" :type :human}))

(defn- setup-cards
  "The owner's held rows from the seed's two setup watches, in the held
  read's order."
  [eng]
  (filterv #(contains? #{"Setting up" "Next steps"} (get-in % [:watch :name]))
           (:rows (watches/held-read eng nil owner))))

(def ^:private card-notes
  {:connect "1. In Claude (claude.ai or the Claude app), open Settings, then Connectors, and add a custom connector.\n2. Name it after your house and give it this engine's address\n3. Claude sends you here to sign in with your house account; allow it.\n4. Open a new chat with that connector on and say what you want this house to do. The Claude you connect is your mayor.\nYou know it worked when Claude answers from this house.\nAlready added it? Open a chat with it on; if Claude says it needs to sign in again, reconnect it."
   :localfire "Open localfire on 127.0.0.1:8112 and give it your Claude token: paste the one `claude setup-token` prints."
   :github "Paste your GitHub token: a fine-grained token on the repository, with contents, pull requests, actions and workflows read and write. It is never shown back."
   :house "Write a paragraph on what this house runs for you, in your own words. The onboarding seat turns it into a tree of parts for you to agree to."})

(defn- seats-named [eng nm]
  (filterv #(= nm (get-in % [:data :name])) (dev/rows eng :seat)))

(deftest a-fresh-engines-first-page-is-its-setup-cards
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed "factory") {:params params})
        pull (str (get-in out [:refs :pull :id]))
        secret (str (:id (named eng :secret "github")))
        house (named eng :org_plan "house")
        cards (setup-cards eng)]
    (is (= "10ca1f12-e000-4000-8000-000000000001" pull))
    (testing "the two required cards, then the two optional ones, in table order"
      (is (= [["member" "tim" "Setting up"]
              ["runner_link" pull "Setting up"]
              ["secret" secret "Next steps"]
              ["org_plan" (str (:id house)) "Next steps"]]
             (mapv (juxt :kind :id #(get-in % [:watch :name])) cards))
          (pr-str cards)))
    (testing "each with its solo text: a solo engine names no URL, so the hole is left out"
      (is (= (mapv card-notes [:connect :localfire :github :house]) (mapv :note cards))))
    (testing "the house plan is drafting, with no brief and no onboarding seat yet"
      (is (= "drafting" (state-of house)))
      (is (empty? (str (get-in house [:data :brief]))))
      (is (empty? (seats-named eng "onboarding-house"))))
    (testing "no setup quest, no first ticket and no Meet your mayor card"
      (is (empty? (dev/rows eng :quest)))
      (is (empty? (dev/rows eng :ticket)))
      (is (not-any? #(.contains (str (:note %)) "Meet your mayor")
                    (:rows (watches/held-read eng nil owner)))))))

(deftest a-hosted-engine-says-its-hosted-text
  (let [eng (booted)
        _ (seed/load! eng (seed/read-seed "factory") {:params params})
        eng (assoc eng :oidc {:app-url "https://work.example/"})
        [connect seats-run] (setup-cards eng)]
    (is (= "Seats run once this engine's runner has pulled for the first time. That is the operator's to start; nothing here is yours to do."
           (:note seats-run))
        (pr-str seats-run))
    (testing "and Connect your Claude names the engine's URL"
      (is (.contains (str (:note connect)) "address:\nhttps://work.example") (:note connect))
      (is (.contains (str (:note connect)) "/api/-/mcp\n3. Claude sends you here"))
      (is (.contains (str (:note connect)) "The Claude you connect is your mayor.")))))

(deftest a-hosted-engine-files-let-seats-run-in-next-steps
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed "factory") {:params (assoc params :hosted "true")})
        pull (str (get-in out [:refs :pull :id]))
        eng (assoc eng :oidc {:app-url "https://work.example/"})
        cards (setup-cards eng)
        held (watches/held-read eng nil owner)]
    (testing "Setting up holds only the owner's step; the runner's first pull waits in Next steps"
      (is (= [["member" "tim" "Setting up"]
              ["runner_link" pull "Next steps"]
              ["secret" (str (:id (named eng :secret "github"))) "Next steps"]
              ["org_plan" (str (:id (named eng :org_plan "house"))) "Next steps"]]
             (mapv (juxt :kind :id #(get-in % [:watch :name])) cards))
          (pr-str cards)))
    (testing "Next steps is not required, so the card may be set aside"
      (is (= {"Setting up" true "Next steps" false}
             (select-keys (into {} (map (juxt :name :required)) (:watches held))
                          ["Setting up" "Next steps"]))))
    (testing "and it still says the owner has nothing to do"
      (is (.contains (str (:note (second cards))) "nothing here is yours to do")))))

(deftest each-card-says-what-it-is-and-who-acts
  (let [eng (booted)
        _ (seed/load! eng (seed/read-seed "factory") {:params params})
        cards (setup-cards eng)
        hosted (setup-cards (assoc eng :oidc {:app-url "https://work.example/"}))]
    (testing "each card is headed by its step in words, not its row"
      (is (= ["Connect your Claude" "Let seats run" "GitHub access" "Say what the house is for"]
             (mapv :summary cards))))
    (testing "the house card's button is its one door"
      (is (= "write_brief" (:door (peek cards))) (pr-str (peek cards))))
    (testing "on a hosted engine the seats card says the owner has nothing to do"
      (is (.contains (str (:note (second hosted))) "nothing here is yours to do")))))

(deftest each-card-leaves-when-its-step-is-done
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed "factory") {:params params})
        pull (str (get-in out [:refs :pull :id]))
        secret (str (:id (named eng :secret "github")))
        house (str (:id (named eng :org_plan "house")))
        kinds #(mapv :kind (setup-cards eng))]
    (testing "the pull link's first pull clears Let seats run"
      (runner-links/seen! eng pull (java.time.Instant/now))
      (is (= ["member" "secret" "org_plan"] (kinds))))
    (testing "the GitHub token clears GitHub access"
      (inv/invoke! eng :secret secret :replace {:value "github_pat_0123456789"}
                   {:principal owner
                    :if-match (inv/etag :secret secret (:version (named eng :secret "github")))})
      (is (= ["member" "org_plan"] (kinds))))
    (testing "the house's brief clears its card and opens onboarding-house"
      (inv/invoke! eng :org_plan house :write_brief
                   {:brief "Our household: meals, chores and the week ahead."}
                   {:principal owner})
      (is (= ["member"] (kinds)))
      (is (= ["active"] (mapv state-of (seats-named eng "onboarding-house")))))))

(deftest a-required-card-stays-and-an-optional-one-may-wait
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed "factory") {:params params})
        pull (str (get-in out [:refs :pull :id]))
        secret (str (:id (named eng :secret "github")))
        mark! (fn [watch action id kind]
                (try (inv/invoke! eng :watch (str (:id (named eng :watch watch))) action
                                  {:row_id id :row_kind kind} {:principal owner})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e))))
        shown #(set (map :id (setup-cards eng)))]
    (testing "a required card refuses dismiss"
      (is (some? (mark! "Setting up" :dismiss pull "runner_link")))
      (is (contains? (shown) pull)))
    (testing "an optional card accepts dismiss"
      (is (nil? (mark! "Next steps" :dismiss secret "secret")))
      (is (not (contains? (shown) secret))))
    (testing "and comes back with undismiss"
      (is (nil? (mark! "Next steps" :undismiss secret "secret")))
      (is (contains? (shown) secret)))))

(deftest an-engine-seeded-before-the-setup-cards-is-not-given-them
  (let [eng (booted)
        factory (seed/read-seed "factory")
        ;; the seed as it stood before: no `:fresh` step, and a first
        ;; ticket whose groom a pinned quest leads to
        older (update factory :steps
                      #(into (vec (remove :fresh %))
                             [{:as :owner :kind :ticket :ref :first-ticket
                               :create {:title "Add the waymark repo kit" :type "feature"
                                        :priority 1 :repo [:param :repo]
                                        :detail "Add the two files of the waymark repo kit."}}
                              {:as :owner :kind :quest :ref :setup
                               :create {:self [:self :first-ticket] :action "groom"
                                        :title "Set up the factory"}}
                              {:as :owner :kind :quest :on :setup :action "pin"}]))
        _ (seed/load! eng older {:params params})
        quest (first (dev/rows eng :quest))
        watch-count (count (dev/rows eng :watch))
        out (seed/load! eng factory {:params params})]
    (is (true? (get-in quest [:data :pinned])) "the older seed pinned its quest")
    (is (false? (:seeded out)) (pr-str out))
    (is (= 0 (get-in out [:added :steps])) "no new rows")
    (is (empty? (dev/rows eng :org_plan)))
    (is (= watch-count (count (dev/rows eng :watch))) "no new watches")
    (is (empty? (setup-cards eng)))
    (is (= quest (first (dev/rows eng :quest))) "its quest untouched")))

;; ── the trial house (ticket 5c7788cd) ─────────────────────────────────────

(def ^:private trial-params
  "The same boot with `trial`, as WAYMARK10_SEED_PARAMS spells it."
  (assoc params :trial "true"))

(def ^:private forge-writes
  ["bench.secret" "bench.merge" "bench.dispatch" "bench.repo_create"])

(defn- steps-of
  "The factory seed's steps under one ref, in order."
  [ref]
  (filterv #(= ref (:ref %)) (:steps (seed/read-seed "factory"))))

(defn- without-approvals [step]
  (update-in step [:create :powers] (partial mapv #(dissoc % :approval))))

(deftest each-trial-step-is-its-ordinary-step-but-for-the-person
  (testing "the bench: the same row, its four forge writes on a reason"
    (let [[plain trial :as both] (steps-of :bench)]
      (is (= 2 (count both)))
      (is (= :trial (:unless-param plain)))
      (is (= :trial (:when-param trial)))
      (is (= (dissoc (without-approvals plain) :unless-param)
             (dissoc (without-approvals trial) :when-param)))
      (is (= (zipmap forge-writes (repeat "person"))
             (into {} (keep #(when (:approval %) [(:power %) (:approval %)]))
                   (get-in plain [:create :powers]))))
      (is (= (zipmap forge-writes (repeat "why"))
             (into {} (keep #(when (:approval %) [(:power %) (:approval %)]))
                   (get-in trial [:create :powers]))))))
  (testing "the mail: the same row, its send on a reason"
    (let [[plain trial :as both] (steps-of :mail)]
      (is (= 2 (count both)))
      (is (= [:mail_url :trial] (:when-param trial)))
      (is (= (dissoc (without-approvals plain) :when-param :unless-param)
             (dissoc (without-approvals trial) :when-param)))
      (is (= ["person" "why"]
             (mapv #(get-in % [:create :powers 1 :approval]) both)))))
  (testing "the mayor: the same seat, its scope widened and nothing taken"
    (let [[plain trial :as both] (steps-of :mayor)
          plain-scope (get-in plain [:with :scope])
          trial-scope (get-in trial [:with :scope])]
      (is (= 2 (count both)))
      (is (= (-> plain (dissoc :unless-param) (update :with dissoc :scope))
             (-> trial (dissoc :when-param) (update :with dissoc :scope))))
      (doseq [[p t] (map vector plain-scope trial-scope)]
        (is (= (dissoc p :actions) (dissoc t :actions)) (:kind p))
        (is (every? (set (:actions t)) (:actions p)) (:kind p)))
      (is (= ["repo_policy" "runner_link" "runner_provider" "secret"]
             (mapv :kind (drop (count plain-scope) trial-scope)))))))

(defn- log-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- sat-with
  "A session `who` sits with key `k` through the real `waymark_sit`."
  [eng k who]
  (let [sid (mcp/open-session! eng)
        r (mcp/call-tool eng (mcp/door eng)
                         {:principal who :mcp-session-id sid}
                         "waymark_sit" {:key k})]
    (is (false? (:isError r)) (str (get-in r [:content 0 :text])))
    sid))

(defn- invoked
  "One `waymark_invoke` through the sat session: did it go, and what it
  said."
  [eng sid who args]
  (let [r (mcp/call-tool eng (mcp/door eng)
                         {:principal who :mcp-session-id sid}
                         "waymark_invoke" args)]
    {:went (not (:isError r)) :text (str (get-in r [:content 0 :text]))}))

(deftest an-ordinary-house-keys-no-mayor-from-inside
  (doseq [ps [params (assoc params :trial "false")]]
    (let [eng (booted)
          out (seed/load! eng (seed/read-seed "factory") {:params ps})]
      (is (true? (:seeded out)) (pr-str out))
      (is (false? (seed/trial-marked? eng "factory")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not loaded with `trial`"
                            (main/mint-trial-key! eng)))
      (is (= "person" (str (get-in (named eng :mcp_server "bench")
                                   [:data :powers 17 :approval])))))))

(deftest a-trial-house-is-sat-with-nobody-present
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed "factory") {:params trial-params})
        model-id (str (get-in out [:refs :model :id]))]
    (is (true? (:seeded out)) (pr-str out))
    (testing "one bench, born dark, its four forge writes on a reason"
      (let [benches (filter #(= "bench" (get-in % [:data :name])) (dev/rows eng :mcp_server))
            powers (into {} (map (juxt :power identity))
                         (get-in (first benches) [:data :powers]))]
        (is (= 1 (count benches)))
        (is (= "dark" (state-of (first benches))))
        (doseq [p forge-writes]
          (is (= "why" (str (get-in powers [p :approval]))) p))))
    (testing "one mayor, and the marker records the trial"
      (is (= 1 (count (seats-named eng "mayor"))))
      (is (true? (seed/trial-marked? eng "factory"))))
    (let [k (main/mint-trial-key! eng)
          mayor (named eng :seat "mayor")
          ;; the trial's Claude: a connector on the owner's sign-in, as
          ;; `sat-as-mayor`'s; the mayor is an interactive seat, and a
          ;; bare agent is refused its sit ("A person sits here")
          who (assoc (t/principal {:id "connector:tim" :type :agent
                                   :display "A trial's Claude"})
                     :acts-for "tim")
          sid (sat-with eng k who)
          go (fn [kind action input & [id]]
               (invoked eng sid who (cond-> {:kind kind :action action :input input}
                                      id (assoc :id (str id)))))]
      (testing "one command keys the mayor, and its log says the command did"
        (is (some #(and (= "offer_key" (name (:action %)))
                        (str/includes? (pr-str %) main/mint-key-actor))
                  (log-of eng :seat (:id mayor)))))
      (testing "the sat mayor creates an org_plan"
        (let [r (go "org_plan" "create" {:name "trial"})]
          (is (:went r) (:text r))))
      (testing "the sat mayor creates an onboarding seat"
        (let [tmpl (edn/read-string (slurp (io/resource "seats/onboarding.edn")))
              body (-> (seed/filled (:seat tmpl) {:org "trial" :repo "tim/widgets"}
                                    (:template tmpl))
                       (assoc :name "onboarding-trial" :held_for [model-id]))
              r (go "seat" "create" body)]
          (is (:went r) (:text r))
          (is (= 1 (count (seats-named eng "onboarding-trial"))))))
      (testing "the sat mayor creates a repo_policy"
        (let [r (go "repo_policy" "create" {:repository "tim/gadgets"
                                            :required_checks ["tests"]})]
          (is (:went r) (:text r))))
      (testing "the sat mayor parks and unparks a seat"
        (let [code-seat (:id (named eng :seat "code-seat"))
              parked (go "seat" "park" {} code-seat)]
          (is (:went parked) (:text parked))
          (is (= "parked" (state-of (named eng :seat "code-seat"))))
          (let [r (go "seat" "unpark" {} code-seat)]
            (is (:went r) (:text r))
            (is (= "active" (state-of (named eng :seat "code-seat"))))))))))

(def ^:private trial-closes
  "The cracks of an ordinary house that a trial house's mayor closes,
  each [kind state crack door remedy]: the doors its wider scope holds.
  repo_policy create and restate close change's remedies; seat park
  closes domain restate's; runner_link and secret create, restate and
  restore close theirs and the schedule mends'. A no_exit the mayor's
  doors now leave turns person_only (`trial-opens`): the mayor is the
  owner's interactive seat, so a person sits it."
  #{["change" "failing" "remedy_unheld" "submit" "repo_policy/create"]
    ["change" "failing" "remedy_unheld" "submit" "repo_policy/restate"]
    ["change" "open" "remedy_unheld" "submit" "repo_policy/create"]
    ["change" "open" "remedy_unheld" "submit" "repo_policy/restate"]
    ["change" "submitted" "remedy_unheld" "submit" "repo_policy/create"]
    ["change" "submitted" "remedy_unheld" "submit" "repo_policy/restate"]
    ["domain" "active" "remedy_unheld" "restate" "seat/park"]
    ["org_plan" "drafting" "no_exit" nil nil]
    ["org_plan" "live" "no_exit" nil nil]
    ["org_plan" "proposed" "no_exit" nil nil]
    ["plan_part" "dropped" "no_exit" nil nil]
    ["runner_link" nil "remedy_unheld" "create" "runner_link/create"]
    ["runner_link" "broken" "no_exit" nil nil]
    ["runner_link" "broken" "remedy_unheld" "restate" "runner_link/restate"]
    ["runner_link" "live" "no_exit" nil nil]
    ["runner_link" "live" "remedy_unheld" "restate" "runner_link/restate"]
    ["runner_link" "retired" "no_exit" nil nil]
    ["schedule" "broken" "remedy_unheld" "mend" "runner_link/restate"]
    ["schedule" "broken" "remedy_unheld" "mend" "runner_link/restore"]
    ["schedule" "held" "remedy_unheld" "mend" "runner_link/restate"]
    ["schedule" "held" "remedy_unheld" "mend" "runner_link/restore"]
    ["schedule" "pending" "remedy_unheld" "mend" "runner_link/restate"]
    ["schedule" "pending" "remedy_unheld" "mend" "runner_link/restore"]
    ["seat" "active" "no_exit" nil nil]
    ["secret" nil "remedy_unheld" "create" "secret/create"]})

(def ^:private trial-opens
  "The cracks only a trial house has, each [kind state crack door
  remedy]: each a no_exit of an ordinary house that the trial's
  interactive mayor now holds a door out of."
  #{["org_plan" "drafting" "person_only" nil nil]
    ["org_plan" "live" "person_only" nil nil]
    ["org_plan" "proposed" "person_only" nil nil]
    ["plan_part" "dropped" "person_only" nil nil]
    ["runner_link" "broken" "person_only" nil nil]
    ["runner_link" "live" "person_only" nil nil]
    ["runner_link" "retired" "person_only" nil nil]
    ["seat" "active" "person_only" nil nil]})

(defn- crack-tuples [eng]
  (into #{} (map (juxt :kind :state :crack :door :remedy))
        (:cracks (cracks/report eng (constantly true) {}))))

(deftest a-trial-houses-cracks-are-on-record-beside-an-ordinary-ones
  (let [plain (booted)
        trial (booted)]
    (seed/load! plain (seed/read-seed "factory") {:params params})
    (seed/load! trial (seed/read-seed "factory") {:params trial-params})
    (let [p (crack-tuples plain)
          t (crack-tuples trial)]
      (is (= trial-closes (reduce disj p t)) "the cracks the trial's mayor closes")
      (is (= trial-opens (reduce disj t p)) "the cracks only a trial house has"))))
