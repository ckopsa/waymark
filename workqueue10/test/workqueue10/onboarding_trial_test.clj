(ns workqueue10.onboarding-trial-test
  "The `onboarding-trial` seed and its count (ticket b5518453): the seed
  walks the factory seed's steps by reference with `trial` and fixed
  params, a walled boot makes its bench and mail at the wall, and the
  count reads only what happened after the seed.
  No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.onboarding-trial-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp.bench :as mcp-bench]
            [waymark10.server.mcp-servers :as mcp-servers]
            [waymark10.server.roles :as roles]
            [waymark10.server.runner-links :as runner-links]
            [waymark10.server.seed :as seed]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [factory10.bench :as bench]
            [factory10.resources.org-plan :as org-plan]
            [factory10.sources.forge :as forge]
            [waymark10.wire :as wire]
            [workqueue10.main :as main]
            [workqueue10.onboarding-trial :as trial]))

(def ^:private wall "http://wall.invalid/mcp")

(defn- booted []
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (main/factory-only-resources)
                            :suppress-mirror-refresh true})]
    (runner-links/ensure-providers! eng)
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    eng))

(defn- server [eng nm]
  (first (filter #(= nm (get-in % [:data :name])) (dev/rows eng :mcp_server))))

(deftest the-seed-is-the-factory-seed-with-trial
  (let [factory (seed/read-seed "factory")
        s (seed/read-seed trial/seed-name)]
    (is (nil? (:extends s)))
    (is (= (:steps factory) (:steps s)) "the factory's steps, not a copy")
    (is (= "Riverside Vet Clinic's owner" (get-in s [:cast :owner :display])))
    (is (= (get-in factory [:cast :claude]) (get-in s [:cast :claude])))
    (is (true? (get-in s [:params :trial])))
    (is (= #{"bench" "mail"} (set (map :server (:walled s)))))
    (is (every? (set (keys (get-in s [:rehearsal :effects])))
                ["bench__prepare" "bench__read" "bench__find" "bench__edit"
                 "bench__edit_many" "bench__check" "bench__submit"
                 "bench__feedback" "bench__merge" "bench__create_repo"
                 "bench__artifact" "mail__send" "bench__status"
                 "bench__enroll"]))))

(def ^:private engine-calls
  "Every bench tool the engine calls with its own hand on a trial's
  path (ticket 01b8befa): the sit's prepare, read and feedback;
  submit's status and submit; discard; a policy's enroll and unenroll;
  the merge line's update_branch, merge and rerun; the findings pass's
  artifact. train_* is absent: the trial's policy runs no train."
  ["bench__prepare" "bench__read" "bench__feedback" "bench__status"
   "bench__submit" "bench__discard" "bench__enroll" "bench__unenroll"
   "bench__update_branch" "bench__merge" "bench__rerun" "bench__artifact"])

(defn- wall-of
  "A fake wall: it answers each call from the seed's own `:rehearsal
  :effects`, the recording whole as one text part, as the clone's wall
  does, and names each call it holds no recording for in `missing`."
  [s missing]
  (let [effects (get-in s [:rehearsal :effects])]
    (fn [_method {:keys [name]}]
      (if-some [rec (get effects (str name))]
        {:content [{:type "text" :text (wire/write-json rec)}]}
        (do (swap! missing conj (str name))
            {:content [{:type "text" :text "This is a clone; the rehearsal seeds no fake."}]
             :isError true})))))

(deftest every-call-the-engine-makes-has-a-recording
  (let [s (seed/read-seed trial/seed-name)
        missing (atom [])
        ctx {:services {:bench-rpc (wall-of s missing)}}
        asked (fn [tool args]
                (bench/ask ctx (keyword (subs tool (count "bench__"))) args))]
    (doseq [tool engine-calls]
      (testing tool
        (let [answer (asked tool {:repo "trial/orgs" :branch "orgs/house"})]
          (is (map? answer) (str tool " answered nothing"))
          (is (nil? (:refused answer)))
          (is (nil? (:status answer)) "the recording's answer, not the recording"))))
    (is (= [] @missing) "the first call with no recording")
    (testing "submit's status is the agent's edit, so submit goes on"
      (let [st (asked "bench__status" {:repo "trial/orgs" :branch "orgs/house"})]
        (is (pos? (long (:dirty st))))
        (is (= (get-in s [:rehearsal :effects "bench__prepare" :result :head])
               (:head st)))))))

(deftest the-sit-reads-a-worktree-from-the-wall
  ;; the second trial's sit said the bench did not answer although
  ;; prepare has a recording: the wall answers the recording as text
  (let [s (seed/read-seed trial/seed-name)
        rec (get-in s [:rehearsal :effects "bench__prepare"])
        wall ((wall-of s (atom [])) "tools/call" {:name "bench__prepare"})]
    (is (= (:result rec) (mcp-bench/bench-payload wall)))
    (is (= (:result rec) (mcp-bench/bench-payload {:structuredContent {:result rec}})))
    (is (= {:head "x" :status "clean"}
           (mcp-bench/bench-result {:structuredContent {:result {:head "x" :status "clean"}}}))
        "a rig's own answer is read as it is")
    (is (nil? (mcp-bench/bench-payload
               ((wall-of {} (atom [])) "tools/call" {:name "bench__prepare"}))))))

(deftest a-seed-extending-itself-is-refused
  (is (thrown? clojure.lang.ExceptionInfo
               (seed/extended {:seed "loop" :extends "loop"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (seed/extended {:seed "orphan" :extends "no-such-seed"}))))

(deftest a-walled-boot-makes-the-trial-bench-and-mail-at-the-wall
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed trial/seed-name) {:params {} :wall-url wall})]
    (is (true? (:seeded out)) (pr-str out))
    (testing "the trial's own rows, at the wall's address"
      (is (= wall (get-in (server eng "bench") [:data :url])))
      (is (= wall (get-in (server eng "mail") [:data :url])))
      (is (= 1 (count (filter #(= "bench" (get-in % [:data :name])) (dev/rows eng :mcp_server)))))
      (is (= "why" (some #(when (= "bench.merge" (:power %)) (:approval %))
                         (get-in (server eng "bench") [:data :powers])))))
    (is (seed/trial-marked? eng trial/seed-name))
    (testing "the count reads nothing the seed did"
      (let [c (trial/count-of eng (trial/owner-of))]
        (is (= "trial-owner" (:owner c)))
        (is (= [] (:owner_moves c)))
        (is (= 0 (get-in c [:totals :owner_steps])))))))

(deftest the-house-plan-and-its-seat-work-in-the-trial-repository
  ;; ticket 36b1cd39: the first trial's submit was refused, its change
  ;; and its seat in ckopsa/waymark, which has no policy here
  (let [eng (booted)
        out (seed/load! eng (seed/read-seed trial/seed-name) {:params {} :wall-url wall})
        named (fn [kind n] (first (filter #(= n (get-in % [:data :name])) (dev/rows eng kind))))
        house (str (:id (named :org_plan "house")))
        _ (inv/invoke! eng :org_plan house :write_brief
                       {:brief "A vet clinic: appointments, reminders and invoices."}
                       {:principal (t/principal {:id "trial-owner" :type :human})})
        seat (named :seat "onboarding-house")
        benches (filter #(str/starts-with? (str (:kind %)) "bench.")
                        (get-in seat [:data :scope]))
        reads? #(contains? #{"bench.find" "bench.read"} (str (:kind %)))]
    (is (true? (:seeded out)) (pr-str out))
    (is (some? seat) "write_brief seats onboarding-house")
    (testing "its writes name the trial's repository"
      (is (seq (remove reads? benches)))
      (doseq [e (remove reads? benches)]
        (is (= "trial/orgs" (get-in e [:filter :repo])) (str (:kind e)))))
    (testing "its reads name the trial's repository and the kit, once each"
      (doseq [e (filter reads? benches)]
        (is (= "trial/orgs,ckopsa/waymark" (get-in e [:filter :repo])) (str (:kind e)))))
    (testing "its change is minted in trial/orgs, which has a policy"
      (is (= {:repo "trial/orgs" :reference ["ckopsa/waymark"]}
             (mcp-bench/seat-repositories seat)))
      (is (some #(= "trial/orgs" (str (get-in % [:data :repository])))
                (filter #(= "active" (name (:state %))) (dev/rows eng :repo_policy)))))
    (testing "the step names no kit fallback"
      (is (not (str/includes? (str (get-in (named :org_plan "house")
                                           [:data :onboarding_note :sentence]))
                              "no orgs repository"))))))

(def ^:private rig-heads
  "The key the rig names a head by in each answer that carries one, as
  the engine reads it: prepare, status and discard `head`, submit
  `commit` (ticket b6896893), merge `sha`."
  {"bench__prepare" :head "bench__status" :head "bench__discard" :head
   "bench__submit" :commit "bench__merge" :sha})

(deftest every-recording-spells-a-head-as-the-rig-does
  ;; ticket daf316d0: a fake that spells a key differently from the rig
  ;; is how submit's `head_sha` got through
  (let [effects (get-in (seed/read-seed trial/seed-name) [:rehearsal :effects])
        spellings #{:head :head_sha :commit :sha}]
    (doseq [[tool k] rig-heads]
      (testing tool
        (let [result (get-in effects [tool :result])]
          (is (string? (get result k)) (str tool " names its head as " k))
          (is (= #{k} (set (filter spellings (keys result))))
              "and by no other spelling"))))))

(defn- wall-client
  "The wall behind one `mcp_server` row, as that row's client: tools/list
  answers the bare names the seed records under the row's prefix, and
  tools/call the recording whole as one text part, as `wall-of` does."
  [s row]
  (let [prefix (str (get-in row [:data :name]) "__")
        mine (into {}
                   (keep (fn [[k v]]
                           (when (str/starts-with? (str k) prefix)
                             [(subs (str k) (count prefix)) v])))
                   (get-in s [:rehearsal :effects]))]
    (fn [method params]
      (case (str method)
        "tools/list" {:tools (mapv (fn [nm] {:name nm
                                             :description (str "The wall's " nm ".")
                                             :inputSchema {:type "object"}})
                                   (sort (keys mine)))}
        "tools/call" (if-some [rec (get mine (str (:name params)))]
                       {:content [{:type "text" :text (wire/write-json rec)}]}
                       {:content [{:type "text"
                                   :text "This is a clone; the rehearsal seeds no fake."}]
                        :isError true})
        (throw (ex-info (str "the wall speaks no " method) {}))))))

(defn- walled
  "`booted`, with the seed's wall as every `mcp_server` row's client and
  the engine's own dispatcher as `:bench-rpc`, as a clone's boot wires
  it. → [engine rpc]."
  [s]
  (let [eng-ref (atom nil)
        rpc (mcp-servers/rpc-of eng-ref)
        eng (engine/engine {:storage (memory/storage)
                            :resources (main/factory-only-resources)
                            :suppress-mirror-refresh true
                            :services {:mcp-servers {:client-fn #(wall-client s %)}
                                       :bench-rpc rpc}})]
    (reset! eng-ref eng)
    (runner-links/ensure-providers! eng)
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    [eng rpc]))

(deftest a-walled-clone-goes-from-submit-to-the-plans-business-question
  ;; ticket daf316d0: the house's seat sits, its change is submitted at
  ;; the wall, and the walled findings pass makes the seed's one finding
  ;; the plan's next step
  (let [s (seed/read-seed trial/seed-name)
        [eng rpc] (walled s)
        out (seed/load! eng s {:params {} :wall-url wall})
        named (fn [kind n] (first (filter #(= n (get-in % [:data :name])) (dev/rows eng kind))))
        owner (t/principal {:id "trial-owner" :type :human})
        delegate (assoc (t/principal {:id "connector:trial-owner" :type :agent
                                      :display "The trial's Claude"})
                        :acts-for "trial-owner")
        house (str (:id (named :org_plan "house")))
        _ (inv/invoke! eng :org_plan house :write_brief
                       {:brief "A vet clinic: appointments, reminders and invoices."}
                       {:principal owner})
        house-key "b25ib2FyZGluZy10cmlhbC1ob3VzZS1rZXktMDE"
        _ (inv/invoke! eng :seat (:id (named :seat "onboarding-house")) :offer_key
                       {:key house-key} {:principal owner})
        sid (mcp/open-session! eng)
        call (fn [tool args]
               (mcp/call-tool eng (mcp/door eng)
                              {:principal delegate :mcp-session-id sid} tool args))
        sat (call "waymark_sit" {:key house-key})
        change (fn [] (first (filter #(= (str "org_plan:" house)
                                         (str (get-in % [:data :born_from])))
                                     (dev/rows eng :change))))
        submit-head (get-in s [:rehearsal :effects "bench__submit" :result :commit])]
    (is (true? (:seeded out)) (pr-str out))
    (is (false? (:isError sat)) (str (get-in sat [:content 0 :text])))
    (is (some? (change)) "the sit minted the house's change")
    (testing "the submit takes its head from the wall's recording"
      (let [r (call "waymark_invoke" {:kind "change" :id (str (:id (change)))
                                      :action "submit"
                                      :input {:why "Write the house's org."}})]
        (is (false? (:isError r)) (str (get-in r [:content 0 :text])))
        (is (= submit-head (get-in (change) [:data :head_sha])))))
    (testing "the walled findings pass makes the question the next step"
      (is (= 1 (:findings (forge/walled-findings-pass!
                           {:engine eng :rpc rpc :log-fn (fn [& _] nil)}))))
      (let [plan (named :org_plan "house")]
        (is (= submit-head (get-in plan [:data :findings_sha])))
        (is (= "decision/inquiry/pricing"
               (:key ((get-in org-plan/org-plan [:computed :next_step :fn])
                      plan {}))))))))

(deftest the-tally-counts-the-owner-and-the-refusals
  (let [c (trial/tally
           {:owner "o"
            :log [{:kind :org_plan :resource-id "p1" :action :write_brief
                   :from-state :drafting :to-state :drafting :actor {:id "o"}}
                  {:kind :org_plan :resource-id "p1" :action :propose
                   :actor {:id "claude"}}
                  {:kind :held_call :resource-id "h1" :action :allow :actor "o"}]
            :held [{:id "h1" :state :done :data {:tool "bench__merge" :decided_by "o"}}]
            :sittings [{:id "s1" :data {:seat "seat-1"
                                        :refusal_log [{:guard "plan-agreed"
                                                       :remedies ["org_plan.agree"]
                                                       :door "go_live"}]}}]
            :asks [{:id "a1" :state :approved}]})]
    (is (= ["write_brief" "allow"] (mapv :door (:owner_moves c))))
    (is (= ["p1" "h1"] (mapv :row (:owner_moves c))))
    (is (= {:owner_steps 2 :owner_row_moves 2 :held_calls 1
            :held_calls_allowed_by_owner 1 :refusals 1 :rejections 0
            :agent_calls 0 :approval_requests 1}
           (:totals c)))
    (is (= {:sitting "s1" :seat "seat-1" :guard "plan-agreed"
            :remedies ["org_plan.agree"] :door "go_live" :kind nil :type nil :at nil}
           (first (:refusals c))))))

(deftest the-first-trial-counts-calls-not-row-moves
  ;; ticket 09053f55: write_brief (the engine seats the house in the
  ;; same call), offer_key, and one agree_many over five plan parts
  (let [own (fn [c kind row door from]
              {:kind kind :resource-id row :action door :from-state from
               :to-state :x :actor {:id "o"} :correlation-id c})
        c (trial/tally
           {:owner "o"
            :log (into [(own "c1" :seat "house" :create nil)
                        (own "c1" :org_plan "p1" :write_brief :drafting)
                        (own "c2" :seat "house" :offer_key :active)]
                       (for [i (range 5)]
                         (own "c3" :plan_part (str "part-" i) :agree :proposed)))
            :held []
            :sittings [{:id "s1"
                        :data {:seat "onboarding-house"
                               :served {:waymark_invoke {:calls 9 :bytes 10}
                                        :waymark_get {:calls 3 :bytes 10}}
                               :refusal_log [{:guard "built-in-home" :door "create"}
                                             {:type "no-repo-policy" :door "submit"}]
                               :rejection_log [{:type "schema-invalid" :status 422
                                                :kind "seat" :door "offer_key"
                                                :errors ["key"]}
                                               {:type "schema-invalid" :status 422
                                                :kind "plan_part" :door "create"
                                                :errors ["home"]}]}}]
            :asks []})]
    (is (= {:owner_steps 3 :owner_row_moves 7 :refusals 2 :rejections 2
            :agent_calls 12}
           (select-keys (:totals c) [:owner_steps :owner_row_moves :refusals
                                     :rejections :agent_calls])))
    (is (= [true false false false false false false false]
           (mapv :by_engine (:owner_moves c))))
    (is (= ["key"] (:errors (first (:rejections c)))))
    (is (= "offer_key" (:door (first (:rejections c)))))))

(deftest the-count-starts-after-the-marker
  (is (= [{:resource-id "x"}]
         (trial/after-the-seed [{:resource-id "a"}
                                {:resource-id "seed-onboarding-trial"}
                                {:resource-id "x"}])))
  (is (= [] (trial/after-the-seed [{:resource-id "a"}]))))
