(ns factory10.bench-test
  "The bench, in the engine (bead waymark-fp62.6.3.2, acceptance 1 to
  6).

  WHAT THIS FILE OWNS is the claim the bench is FOR: that a code seat
  reads and edits a checkout through this engine's own door, judged
  and counted there, and that the four tools which decide what a
  change IS — prepare, status, submit, discard — are the engine's and
  cannot be reached by the model at all.

  THE RIG IS A FAKE, AND IT ANSWERS THE REAL CONTRACT. The bench rig
  (bead waymark-fp62.6.3.1, ckopsa/waymark-bench) answers every
  tools/call with the same JSON twice — one text part and
  `structuredContent.result` — and a refusal is that same shape with
  `isError` and a `refused` name in it. The fake below answers exactly
  that, with the rig's own refusal names, so a shape that drifts on
  either side is a test that fails here.

  Memory storage, no database and no network: the fake Gate stands
  behind the very seam `gate-proxy/rpc-of` reads first, and the same
  fake stands behind the doors as `(:services :bench-rpc)`. The OIDC
  harness is mcp_sit_test's, copied whole — a locally minted keypair
  as the family IdP's signing key, so a person can sign in through the
  connector and a Routine's session can present a seat key.

  Run: cd factory10 && clojure -M:test"
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]
            [factory10.main :as main]
            [factory10.mirror :as mirror]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

;; ── the fake rig, answering the contract (6.3.1) ────────────────────

(def ^:private rig-tools
  "The eight tools Gate re-exposes as `bench__<tool>`, as a live
  tools/list answers them. Four of them are powers a scope may name;
  the other four are the engine's, and the map in gate-proxy is what
  says which is which."
  (mapv (fn [nm]
          {:name (str "bench__" nm)
           :description (str "The bench's " nm ".")
           :inputSchema {:type "object"
                         :properties {:repo {:type "string"}
                                      :branch {:type "string"}}
                         :required ["repo" "branch"]}})
        ["prepare" "status" "find" "read" "edit" "pull" "submit" "discard"]))

(def ^:private a-head "1f0c2d3e4a5b60718293a4b5c6d7e8f901234567")
(def ^:private a-commit "9a8b7c6d5e4f30291827364554637281900aabbc")

(defn- state
  "A scriptable rig: what each tool answers, and what it was asked."
  []
  (atom {:calls []
         :answers
         {"bench__prepare" {:repo "ckopsa/waymark" :branch "waymark/one"
                            :base "main" :head a-head :base_head a-head
                            :dirty 2 :created false :default_branch "main"}
          "bench__status" {:repo "ckopsa/waymark" :branch "waymark/one"
                           :head a-head :base "main" :base_head a-head
                           :dirty 2 :paths ["src/a.clj" "src/b.clj"]
                           :ahead 0 :behind 0}
          "bench__submit" {:repo "ckopsa/waymark" :branch "waymark/one"
                           :commit a-commit :pushed true :files 2
                           :lines_added 12 :lines_removed 3}
          "bench__discard" {:repo "ckopsa/waymark" :branch "waymark/one"
                            :head a-head :dirty 0 :dropped false}
          "bench__read" {:repo "ckopsa/waymark" :branch "waymark/one"
                         :path "src/a.clj" :hash "abc"
                         :lines [{:line 1 :text "(ns a)"}]
                         :total_lines 1 :eof true :dropped 900}}}))

(defn- answer!
  "Script one tool's answer — a result map, or a refusal map with
  `:refused` on it."
  [st tool answer]
  (swap! st assoc-in [:answers tool] answer))

(defn- calls-of
  "Every tools/call the rig was asked, in order."
  [st tool]
  (filterv #(= tool (:tool %)) (:calls @st)))

(defn- fake-rig
  "A Gate caller over the state atom — `(fn [method params])`, the
  shape `gate-proxy/rpc-of` answers and the seam both the transport
  and `factory10.bench` read first. It answers tools/list with the
  eight and tools/call with the scripted answer, in the rig's own two
  shapes: one text part and structuredContent.result, with isError
  when the answer carries a refusal."
  [st]
  (fn [method params]
    (cond
      (= "tools/list" method) {:tools rig-tools}

      (= "tools/call" method)
      (do
        (swap! st update :calls conj {:tool (str (:name params))
                                      :arguments (:arguments params)})
        (when (:down @st)
          (throw (ex-info "Gate unreachable" {})))
        (let [answer (get-in @st [:answers (str (:name params))]
                             {:refused "unknown_tool"})]
          {:isError (boolean (:refused answer))
           :content [{:type "text" :text (wire/write-json answer)}]
           :structuredContent {:result answer}}))

      :else (throw (ex-info (str "the fake rig speaks no " method) {})))))

;; ── the house ───────────────────────────────────────────────────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "bench-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "bench-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "bench-key"}}))

(def ^:private colton-claims
  {:sub "colton" :azp "connector" :name "Colton Kopsa"})

(defn- bearer [] {"authorization" (str "Bearer " (mint colton-claims))})

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))

(defn- fresh-engine
  "The factory's three kinds, the capability registry beside them, and
  one fake rig standing in for Gate on both seams."
  [st]
  (let [rig (fake-rig st)]
    (engine/engine {:storage (memory/storage)
                    :resources (conj (vec (main/resources)) caps/capability)
                    :oidc {:issuer issuer :audience audience :jwks jwks
                           :app-url "https://app.test/"
                           :delegate-clients {"connector" "Claude"}}
                    ;; the tests' seam gate-proxy/rpc-of reads FIRST: no
                    ;; URL, no socket, no live Gate in this namespace
                    :gate {:rpc rig}
                    ;; …and the seam the change row's doors read first
                    :services {:bench-rpc rig}})))

(def ^:private a-repository "ckopsa/waymark")

(defn- a-policy!
  [eng extra]
  (:row (inv/create! eng :repo_policy
                     (merge {:repository a-repository
                             :branch_pattern "waymark/*"
                             :base "main"
                             :max_lines 400
                             :opens_pr true
                             :auto_merge true
                             :rounds_per_change 3
                             :formatter "runner"
                             :deny ["*.env"]
                             :orientation "docs/orientation.md"}
                            extra)
                     {:principal person})))

(defn- a-change!
  "One pull request, minted by the mirror as the source would."
  [eng extra]
  (:row (inv/create! eng :change
                     (merge {:change_id "github:ckopsa/waymark#31"
                             :repository a-repository
                             :number 31
                             :title "6.3 The bench"
                             :head_branch "waymark/one"
                             :head_sha a-head}
                            extra)
                     {:principal mirror/source-principal})))

(defn- a-model! [eng nm]
  (:row (inv/create! eng :model
                     {:name nm :display "Bench 1" :vendor "anthropic"
                      :tier "strong"
                      :price_input_per_mtok 3M :price_output_per_mtok 15M
                      :price_cache_read_per_mtok 0.3M
                      :price_cache_write_per_mtok 3.75M}
                     {:principal person})))

;; ── half one: the powers, with no seat at all ───────────────────────
;;
;; waymark_powers and waymark_power are a function of the GRANT, so
;; this half needs no sit: a grant, an agent wearing it, and the
;; message layer the transport drives.

(def ^:private clerk-model "bench-clerk-model")

(def ^:private clerk
  (t/principal {:id "bench-clerk" :type :agent :display "Bench clerk"
                :model clerk-model}))

(defn- power-world
  "An engine where the four bench capabilities are registered and the
  clerk wears a grant naming `tokens`. → {:eng :session :state}."
  [tokens]
  (let [st (state)
        eng (fresh-engine st)]
    (doseq [token ["bench.read" "bench.find" "bench.edit" "bench.pull"]]
      (inv/create! eng :capability
                   {:token token
                    :description (str "The bench's " token ".")
                    :enforced_by "this engine's own gate door"}
                   {:principal person}))
    (let [grant (:row (inv/create! eng :grant
                                   {:audience (:id clerk)
                                    :scope (mapv (fn [tk] {:kind tk :actions []})
                                                 tokens)}
                                   {:principal person}))
          _ (inv/invoke! eng :grant (:id grant) :accept {} {:principal clerk})
          ;; an open sitting, so the byte counter has a row to write on
          model (a-model! eng clerk-model)
          seat (:row (inv/create! eng :seat
                                  {:name "power-seat"
                                   :charter "Read the code and say what is wrong."
                                   :scope [{:kind "capability" :actions []}]
                                   :standing_ttl_seconds 604800
                                   :cadence_seconds 3600
                                   :budget_usd_per_week 5M
                                   :sitting_budget_tokens 60000}
                                  {:principal person}))
          sitting (:row (inv/create! eng :sitting
                                     {:seat (:id seat) :model (:id model)
                                      :grant (str (:id grant))}
                                     {:principal clerk}))]
      {:eng eng
       :state st
       :sitting (:id sitting)
       :session {:principal clerk
                 :visibility (grants/visibility eng (str (:id grant)) clerk)}})))

(defn- served-line
  "The sitting's `served` line for one tool, as stored."
  [{:keys [eng sitting]} tool]
  (get-in (store/with-tx (:storage eng)
            (fn [tx] (store/load-row (:storage eng) tx :sitting
                                     (str sitting) {})))
          [:data :served (keyword tool)]))

(defn- tool!
  "One tools/call through the whole message layer — the path the
  transport drives, so the byte counter runs too."
  [{:keys [eng session]} tool-name args]
  (get-in (mcp/message eng (mcp/door eng) (get-in eng [:gate :rpc]) session
                       {:jsonrpc "2.0" :id 1 :method "tools/call"
                        :params {:name tool-name :arguments args}})
          [:result]))

(defn- text-of [result]
  (str/join " " (keep :text (:content result))))

(defn- doc-of [result]
  (wire/read-json (str (get-in result [:content 0 :text]))))

;; ── acceptance 1 ────────────────────────────────────────────────────

(deftest a-scope-of-two-bench-powers-sees-two-tools-and-not-the-other-six
  (let [w (power-world ["bench.read" "bench.find"])
        doc (doc-of (tool! w "waymark_powers" {}))
        named (into #{} (map name) (concat (keys (:links doc))
                                           (keys (:actions doc))))]
    (is (= #{"bench__read" "bench__find"} named)
        "the live tool list intersected with the grant: the two the
         scope names, and not the edit, not the pull, and not one of
         the four the engine keeps")
    (testing "and a scope naming all four powers still sees four"
      (let [w4 (power-world ["bench.read" "bench.find" "bench.edit"
                             "bench.pull"])
            doc4 (doc-of (tool! w4 "waymark_powers" {}))]
        (is (= #{"bench__read" "bench__find" "bench__edit" "bench__pull"}
               (into #{} (map name) (concat (keys (:links doc4))
                                            (keys (:actions doc4)))))
            "prepare, status, submit and discard are on no token at
             all, so no scope can ever reach them")))))

;; ── acceptance 2 ────────────────────────────────────────────────────

(deftest the-engines-four-tools-are-refused-under-every-scope
  (doseq [tokens [["bench.read" "bench.find"]
                  ["bench.read" "bench.find" "bench.edit" "bench.pull"]]]
    (let [w (power-world tokens)]
      (doseq [tool ["bench__prepare" "bench__status" "bench__submit"
                    "bench__discard"]]
        (let [r (tool! w "waymark_power" {:tool tool :arguments {}})]
          (is (true? (:isError r))
              (str tool " must be refused whatever the scope names"))
          (is (empty? (:calls @(:state w)))
              "and refused BEFORE any wire: a tool outside the map does
               not exist through this door, so nothing was asked of the
               rig"))))))

;; ── R-2 · the power door's shaping for the bench ────────────────────

(deftest a-bench-read-is-capped-and-what-the-rig-dropped-lands-on-served
  (let [w (power-world ["bench.read"])
        r (tool! w "waymark_power"
                 {:tool "bench__read"
                  :arguments {:repo a-repository :branch "waymark/one"
                              :path "src/a.clj" :max_bytes 999999}})]
    (is (false? (:isError r)) (text-of r))
    (testing "the cap the caller asked for is clamped to the rig's own"
      (is (= gate/bench-max-bytes
             (:max_bytes (:arguments (first (calls-of (:state w)
                                                      "bench__read")))))
          "a seat may read less than the rig serves, never more"))
    (testing "a cap under the ceiling passes through untouched"
      (let [w2 (power-world ["bench.read"])]
        (tool! w2 "waymark_power"
               {:tool "bench__read"
                :arguments {:repo a-repository :branch "waymark/one"
                            :path "src/a.clj" :max_bytes 4096}})
        (is (= 4096 (:max_bytes (:arguments (first (calls-of (:state w2)
                                                             "bench__read"))))))))

    (testing "and the sitting counts the bytes under the RIG's tool name"
      (let [line (served-line w "bench__read")]
        (is (= 1 (:calls line))
            "a code seat's bill reads read, find, edit and pull apart,
             where one waymark_power line would read them together")
        (is (pos? (long (:bytes line))))
        (is (= 900 (long (:dropped line)))
            "what the RIG capped lands on served beside what the shape
             removed, so served plus dropped is what the whole answer
             would have cost")))))

(deftest an-unchanged-read-counts-its-own-few-bytes-and-nothing-more
  (let [w (power-world ["bench.read"])
        _ (answer! (:state w) "bench__read"
                   {:repo a-repository :branch "waymark/one"
                    :path "src/a.clj" :unchanged true :hash "abc"})
        r (tool! w "waymark_power"
                 {:tool "bench__read"
                  :arguments {:repo a-repository :branch "waymark/one"
                              :path "src/a.clj" :if_hash "abc"}})]
    (is (false? (:isError r)))
    (is (true? (:unchanged (doc-of r)))
        "the rig says the file did not move and sends no lines")
    (is (> 400 (count (text-of r)))
        "…so the seat pays for a sentence rather than for a file")))

;; ── half two: the seat, the sit and the two doors ───────────────────

(def ^:private a-key
  "128 bits of base64url — what a machine mints and no hand types."
  "YmVuY2gtc2VhdC1rZXktMDAwMDAwMDAwMDAw")

(def ^:private charter
  "Read the change and take the door the orientation document asks for.")

(defn- open-seat!
  [eng extra]
  (let [model (a-model! eng "bench-seat-model")
        seat (:row (inv/create!
                    eng :seat
                    (merge {:name "bench-seat"
                            :charter charter
                            :scope [{:kind "change"
                                     :actions ["submit" "discard" "stall"]}]
                            :walk "change"
                            :rows_per_firing 1
                            :held_for [(:id model)]
                            :standing_ttl_seconds 604800
                            :cadence_seconds 3600
                            :budget_usd_per_week 5M
                            :sitting_budget_tokens 60000}
                           extra)
                    {:principal person}))]
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                 {:principal person})
    seat))

(defn- rpc
  "One JSON-RPC message at /api/-/mcp through the real handler."
  [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- call!
  "One tools/call at the real door, answering the parsed tool result."
  [h sid tool-name args]
  (get-in (json (rpc h (assoc (bearer) "mcp-session-id" sid)
                     "tools/call" {:name tool-name :arguments args}))
          [:result]))

(defn- world
  "An engine with the policy, one change, a seat that walks changes,
  and a session sat in it. → {:eng :state :h :sid :seat :change :answer}."
  ([] (world {} {}))
  ([policy-extra change-extra]
   (let [st (state)
         eng (fresh-engine st)
         _ (a-policy! eng policy-extra)
         change (a-change! eng change-extra)
         seat (open-seat! eng {})
         h (engine/handler eng)
         sid (get-in (rpc h (bearer) "initialize"
                          {:protocolVersion mcp/protocol-version
                           :capabilities {}
                           :clientInfo {:name "routine" :version "0"}})
                     [:headers "Mcp-Session-Id"])
         sat (call! h sid "waymark_sit" {:key a-key})]
     {:eng eng :state st :h h :sid sid :seat seat :change change
      :sat sat :answer (doc-of sat)})))

(defn- sitting-of
  "The seat's sitting row, open or closed."
  [{:keys [eng seat]}]
  (first (store/with-tx (:storage eng)
           (fn [tx] (store/query-rows (:storage eng) tx :sitting
                                      {:seat (str (:id seat))}
                                      {:limit 1 :newest-first true})))))

(defn- change-row
  [{:keys [eng change]}]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :change (str (:id change)) {}))))

(defn- submit!
  [{:keys [h sid change]} input]
  (call! h sid "waymark_invoke"
         {:kind "change" :id (str (:id change)) :action "submit"
          :input input}))

;; ── acceptance 3 ────────────────────────────────────────────────────

(deftest the-sit-answers-the-bench-the-orientation-and-what-submit-means
  (let [w (world)
        answer (:answer w)]
    (is (false? (:isError (:sat w))) (text-of (:sat w)))

    (testing "the walk is still the walk"
      (is (= "change" (get-in answer [:walk :kind])))
      (is (= [(str (:id (:change w)))]
             (mapv :id (get-in answer [:walk :rows])))))

    (testing "and the worktree is made before the answer leaves"
      (let [prepares (calls-of (:state w) "bench__prepare")]
        (is (= 1 (count prepares))
            "one prepare for one firing — idempotent, and the engine's
             own hand rather than the seat's")
        (is (= {:repo a-repository :branch "waymark/one" :base "main"}
               (:arguments (first prepares)))
            "the change's own head branch, and the policy's base")))

    (testing "the bench rides in the answer"
      (is (= a-repository (get-in answer [:bench :repo])))
      (is (= "waymark/one" (get-in answer [:bench :branch])))
      (is (= "main" (get-in answer [:bench :base])))
      (is (= a-head (get-in answer [:bench :head])))
      (is (= 2 (get-in answer [:bench :dirty]))
          "an earlier sitting left two paths, and the seat is told so"))

    (testing "beside the path it reads first and what submit will do"
      (is (= "docs/orientation.md" (:orientation answer)))
      (is (str/includes? (:submit_means answer) "pull request"))
      (is (str/includes? (:submit_means answer) "400"))
      (is (str/includes? (:submit_means answer) "3 rounds")))))

(deftest a-branch-the-change-does-not-name-is-made-from-the-policys-pattern
  (let [w (world {} {:head_branch nil})
        prepare (first (calls-of (:state w) "bench__prepare"))]
    (is (= (str "waymark/" (:id (:change w)))
           (:branch (:arguments prepare)))
        "one branch for each change, so the next sitting finds the
         work the last one left")))

(deftest a-dark-rig-costs-the-bench-and-never-the-sit
  (let [st (state)
        eng (fresh-engine st)
        _ (a-policy! eng {})
        change (a-change! eng {})
        _ (open-seat! eng {})
        h (engine/handler eng)
        sid (get-in (rpc h (bearer) "initialize"
                         {:protocolVersion mcp/protocol-version
                          :capabilities {}
                          :clientInfo {:name "routine" :version "0"}})
                    [:headers "Mcp-Session-Id"])
        _ (swap! st assoc :down true)
        sat (call! h sid "waymark_sit" {:key a-key})
        answer (doc-of sat)]
    (is (false? (:isError sat)) "the sit still answers")
    (is (nil? (:bench answer)))
    (is (string? (:bench_note answer))
        "and it says why, in one sentence about the bench")
    (is (= [(str (:id change))] (mapv :id (get-in answer [:walk :rows])))
        "the rows are still there: a seat that cannot reach the bench
         can read its queue and say so")
    (is (= "docs/orientation.md" (:orientation answer))
        "the orientation is the policy's, not the rig's")))

;; ── acceptance 4 ────────────────────────────────────────────────────

(deftest a-clean-worktree-refuses-the-submit
  (let [w (world)
        _ (answer! (:state w) "bench__status"
                   {:repo a-repository :branch "waymark/one" :head a-head
                    :base "main" :base_head a-head :dirty 0 :paths []
                    :ahead 0 :behind 0})
        r (submit! w {:why "Nothing changed, but I am trying anyway."})]
    (is (true? (:isError r)))
    (is (str/includes? (text-of r) "nothing to submit"))
    (is (str/includes? (text-of r) "bench__edit")
        "the refusal names the way out")
    (is (empty? (calls-of (:state w) "bench__submit"))
        "and nothing was committed")
    (is (= "open" (name (:state (change-row w))))
        "the row did not move")))

(deftest a-submit-commits-with-the-seat-and-the-sitting-on-it
  (let [w (world)
        before (sitting-of w)
        r (submit! w {:why "Fix the fixture's table list."})
        call (first (calls-of (:state w) "bench__submit"))
        row (change-row w)]
    (is (false? (:isError r)) (text-of r))

    (testing "the rig was asked to commit the seat's own sentence"
      (is (= "Fix the fixture's table list." (:message (:arguments call))))
      (is (= 400 (:max_lines (:arguments call)))
          "the ceiling is the policy's and never the model's")
      (is (= a-repository (:repo (:arguments call))))
      (is (= "waymark/one" (:branch (:arguments call)))))

    (testing "with the two trailers, so blame maps to an office"
      (let [trailers (set (:trailers (:arguments call)))]
        (is (contains? trailers (str "Waymark-Seat: " (:id (:seat w)))))
        (is (contains? trailers (str "Waymark-Sitting: " (:id before))))))

    (testing "and the row carries the round"
      (is (= "submitted" (name (:state row))))
      (is (= a-commit (get-in row [:data :head_sha])))
      (is (= 1 (get-in row [:data :rounds])))
      (is (= 0 (get-in row [:data :worktree_dirty])))
      (is (= "waymark/one" (get-in row [:data :branch]))))

    (testing "and the round is over: the sitting closed"
      (is (= :closed (:state (sitting-of w)))
          "the push is the end of what this wake had to do")
      (is (= "The round ended: this change was submitted."
             (get-in (sitting-of w) [:data :note]))))))

(deftest a-rejected-push-refuses-and-the-remedy-names-the-pull-power
  (let [w (world)
        _ (answer! (:state w) "bench__submit"
                   {:refused "push_rejected"
                    :commit a-commit
                    :reason "the remote has commits you do not have"
                    :remedy "use pull from head, then submit again"})
        r (submit! w {:why "Fix the fixture's table list."})
        said (text-of r)]
    (is (true? (:isError r)))
    (is (str/includes? said "push_rejected"))
    (is (str/includes? said "bench__pull")
        "the way out is a POWER the seat already holds")
    (is (= "open" (name (:state (change-row w))))
        "the round did not count: nothing landed")
    (is (= :open (:state (sitting-of w)))
        "and the sitting is still open — the round ends on a push that
         landed, not on one that did not")))

(deftest at-the-round-ceiling-the-door-refuses-and-the-stall-moves-the-row
  (let [w (world {:rounds_per_change 2} {})
        change-id (str (:id (:change w)))
        ;; two rounds already spent — the maintenance write
        ;; `bump-counter!` makes, one kind over
        _ (let [st (:storage (:eng w))]
            (store/with-tx st
              (fn [tx]
                (let [row (store/load-row st tx :change change-id {})]
                  (store/update-data! st tx :change change-id
                                      (assoc (:data row) :rounds 2) nil)))))
        r (submit! w {:why "One more try."})
        said (text-of r)]
    (is (true? (:isError r)))
    (is (str/includes? said "2 of the 2 rounds"))
    (is (str/includes? said "change.stall")
        "the refusal names the door that moves the row")
    (is (empty? (calls-of (:state w) "bench__submit"))
        "nothing was committed at the ceiling")

    (testing "and the door it names does move the row"
      (let [stalled (call! (:h w) (:sid w) "waymark_invoke"
                           {:kind "change" :id change-id :action "stall"
                            :input {:why "The same test fails on the base commit."}})]
        (is (false? (:isError stalled)) (text-of stalled))
        (is (= "stuck" (name (:state (change-row w)))))))))

;; ── acceptance 5 ────────────────────────────────────────────────────

(deftest a-discard-throws-the-edits-away-and-keeps-the-branch
  (let [w (world)
        r (call! (:h w) (:sid w) "waymark_invoke"
                 {:kind "change" :id (str (:id (:change w)))
                  :action "discard" :input {}})
        call (first (calls-of (:state w) "bench__discard"))]
    (is (false? (:isError r)) (text-of r))
    (is (= {:repo a-repository :branch "waymark/one"} (:arguments call))
        "no drop_branch at all: a model's discard keeps the branch")
    (is (= 0 (get-in (change-row w) [:data :worktree_dirty])))
    (is (= "open" (name (:state (change-row w))))
        "the change stands; only the worktree moved")))

(deftest a-model-may-not-drop-the-branch-and-a-person-may
  (let [w (world)
        change-id (str (:id (:change w)))
        r (call! (:h w) (:sid w) "waymark_invoke"
                 {:kind "change" :id change-id :action "discard"
                  :input {:drop_branch true}})]
    (is (true? (:isError r)))
    (is (str/includes? (text-of r) "person's act"))
    (is (empty? (calls-of (:state w) "bench__discard")))

    (testing "and a person's own hand drops it"
      (inv/invoke! (:eng w) :change change-id :discard {:drop_branch true}
                   {:principal person})
      (is (true? (:drop_branch (:arguments (first (calls-of (:state w)
                                                            "bench__discard")))))))))

;; ── acceptance 6 ────────────────────────────────────────────────────

(deftest every-refusal-counts-one-on-the-open-sitting
  (let [w (world)
        _ (answer! (:state w) "bench__status"
                   {:repo a-repository :branch "waymark/one" :head a-head
                    :base "main" :base_head a-head :dirty 0 :paths []
                    :ahead 0 :behind 0})
        start (long (or (get-in (sitting-of w) [:data :refusals]) 0))]
    (is (= 0 start) "a fresh sitting has refused nothing")

    (testing "a handler's refusal counts, because it is a 409 like any other"
      (submit! w {:why "Nothing changed, but I am trying anyway."})
      (is (= 1 (long (get-in (sitting-of w) [:data :refusals])))))

    (testing "and a guard's refusal counts beside it"
      (call! (:h w) (:sid w) "waymark_invoke"
             {:kind "change" :id (str (:id (:change w))) :action "discard"
              :input {:drop_branch true}})
      (is (= 2 (long (get-in (sitting-of w) [:data :refusals])))))

    (testing "…and the sitting is still open, so the seat may go on"
      (is (= :open (:state (sitting-of w)))))))

;; ── the bench helper's own arithmetic ───────────────────────────────

(deftest the-branch-pattern-is-a-glob-with-one-star
  (is (bench/matches-pattern? "waymark/one" "waymark/*"))
  (is (bench/matches-pattern? "waymark/fp62.6.3" "waymark/*"))
  (is (not (bench/matches-pattern? "main" "waymark/*")))
  (is (not (bench/matches-pattern? "mainline" "main")))
  (is (bench/matches-pattern? "anything" "*")
      "a policy that wants every branch says so with one star"))

(deftest the-trailers-name-the-office-and-nobody-else
  (testing "a seat's hand names both"
    (is (= ["Waymark-Seat: 01HZ" "Waymark-Sitting: 02AB"]
           (bench/trailers
            {:principal {:id "seat:01HZ" :type :agent}
             :grant {:id "grant-1"}
             :find (fn [kind where _]
                     (when (and (= :sitting kind) (= "grant-1" (:grant where)))
                       [{:id "02AB"}]))}))))
  (testing "a person's hand names neither: a commit is an office's or it is theirs"
    (is (= [] (bench/trailers {:principal {:id "colton" :type :human}})))))
