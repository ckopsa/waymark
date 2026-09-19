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

  THE BENCH IS A ROW HERE, as it is in a deployment
  (waymark-fp62.6.3.3): `fresh-engine` creates one `mcp_server` row
  named `bench`, whose powers map bench.find / bench.read /
  bench.edit / bench.pull to their tools and name prepare, status,
  submit and discard in NO entry. The fake rig is registered as that
  row's client through the engine's `:client-fn` seam
  (waymark10.mcp-servers-test's own pattern), so the row discovers
  the rig's tools at create and every later call rides the same fake.
  The rig speaks its BARE tool names, `read` and `submit`, and the
  row's name is the `bench__` prefix every caller sees.

  Memory storage, no database and no network: the fake rig stands
  behind the row's client seam, and the engine's own dispatcher
  stands behind the doors as `(:services :bench-rpc)`. The OIDC
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
            [waymark10.resource :as r]
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
  "The twelve tools the rig offers, as a live tools/list answers them:
  its OWN bare names, because the `bench__` prefix is the row's name
  and the engine puts it on. Four of them are powers a scope may
  name; the other eight — the four that decide what a change is, the
  three that decide which repositories the rig holds (bead
  waymark-fp62.6.3.7), and `feedback`, which the sit calls with the
  engine's own hand (bead waymark-fp62.6.3.9) — are named by no entry
  of the row's powers here, and that absence is what says which is
  which. The deployment's row also carries `bench.feedback` for a
  seat that asks the rig again itself; that power's own narrowing is
  pinned in waymark10/test/waymark10/narrow_power_test.clj."
  (mapv (fn [nm]
          {:name nm
           :description (str "The bench's " nm ".")
           :inputSchema {:type "object"
                         :properties {:repo {:type "string"}
                                      :branch {:type "string"}}
                         :required ["repo" "branch"]}})
        ["prepare" "status" "find" "read" "edit" "pull" "submit" "discard"
         "enroll" "repos" "unenroll" "feedback"]))

(def ^:private bench-powers
  "The bench row's powers (waymark-fp62.6.3.3): the four the model may
  hold, each on its own token, and prepare, status, submit and
  discard in NO entry at all — a tool no entry names does not exist
  through the power door, whatever the rig offers. Each entry names
  the fields a grant's filter may narrow it by (waymark-fp62.6.3.5);
  the narrow power's own cases live in
  waymark10/test/waymark10/narrow_power_test.clj."
  [{:power "bench.find" :tools ["find"] :why false
    :constraints ["repo" "path"]}
   {:power "bench.read" :tools ["read"] :why false
    :constraints ["repo" "path"]}
   {:power "bench.edit" :tools ["edit"] :why false
    :constraints ["repo" "path"]}
   ;; a pull moves a whole checkout, so no path could narrow one
   {:power "bench.pull" :tools ["pull"] :why false
    :constraints ["repo"]}])

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
                         :total_lines 1 :eof true :dropped 900}
          ;; the three of bead waymark-fp62.6.3.7, in the rig's own
          ;; shapes: the enrolment answers what the rig now holds, and
          ;; the unenrolment says whether it kept the clone
          "bench__enroll" {:name "ckopsa/waymark"
                           :clone_url "https://github.com/ckopsa/waymark"
                           :default_branch "main" :deny ["*.env"]
                           :land "worktree" :bare true :cloned true}
          ;; what the submit caused (bead waymark-fp62.6.3.9), in the
          ;; rig's own shape: the pull request, one finding for each
          ;; red check and each review comment, and the parts of the
          ;; forge the rig could not reach
          "bench__feedback"
          {:repo "ckopsa/waymark" :branch "waymark/one" :target "main"
           :landing nil
           :pull_request {:number 31 :state "open" :head a-head
                          :url "https://github.com/ckopsa/waymark/pull/31"}
           :pipelines [] :statuses [] :comments []
           :findings [{:source "pipeline" :severity "error" :step "gate"
                       :message "FAIL in waymark10.narrow-power-test"
                       :locations [{:path "waymark10/test/waymark10/narrow_power_test.clj"
                                    :line 231}]
                       :url "https://github.com/ckopsa/waymark/actions/runs/7"}
                      {:source "review" :severity "comment" :author "colton"
                       :message "Name the rule in the comment."}]
           :unavailable ["statuses: the forge answered 403"]}
          "bench__repos" {:repos ["ckopsa/waymark"]}
          "bench__unenroll" {:repo "ckopsa/waymark" :kept true}}}))

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
  "The rig itself — `(fn [method params])`, the shape an `mcp_server`
  row's client answers, registered through the engine's `:client-fn`
  seam. It answers tools/list with the eight BARE names and tools/call
  with the scripted answer, in the rig's own two shapes: one text part
  and structuredContent.result, with isError when the answer carries a
  refusal. What it was asked is recorded under the PREFIXED name the
  callers above spell, because that is the name a reader of this file
  is asking about."
  [st]
  (fn [method params]
    (cond
      (= "tools/list" method) {:tools rig-tools}

      (= "tools/call" method)
      (let [named (str "bench__" (:name params))]
        (swap! st update :calls conj {:tool named
                                      :arguments (:arguments params)})
        (when (:down @st)
          (throw (ex-info "Gate unreachable" {})))
        (let [answer (get-in @st [:answers named]
                             {:refused "unknown_tool"})]
          {:isError (boolean (:refused answer))
           :content [{:type "text" :text (wire/write-json answer)}]
           :structuredContent {:result answer}}))

      :else (throw (ex-info (str "the fake rig speaks no " method) {})))))

;; ── the queue a person writes (bead waymark-fp62.6.3.10) ────────────

(def ^:private ask
  "The smallest queue of ASKS: one row that says what to build. The
  household's own `task` kind is workqueue10's and this suite boots
  the factory alone, so the walk gets a kind of its own here — shaped
  the way `task` is shaped, which is the whole point of it. Its
  lifecycle is DATA (`status`), its machine is not, and its default
  filter therefore names a field that is not `state`. A seat may walk
  it because the walk guard asks for one default filter and no
  longer for one over state."
  (r/resource
   {:kind :ask
    :plural "asks"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {data.status}"
    :label-template "{data.title}"
    :schema [:map
             [:title {:examples ["Put the size ceiling on the policy form"]
                      :x-display {:label "What to build"
                                  :help "The one line a person would say out loud."}}
              [:string {:min 1 :max 200}]]
             [:status {:optional true :filter #{:eq :in}
                       :x-display {:label "Where it stands"}}
              [:maybe [:enum "open" "done"]]]]
    :filterable {:state #{:eq :in}}
    :default-filters {:status "open"}
    :actions
    {:complete {:from #{:open} :to :done
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Done is done."}
                :display {:label "Complete" :style :primary :order 1
                          :description "Say the ask is built"}}}}))

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
  "The factory's three kinds and the capability registry beside them,
  one fake rig registered as the `bench` row's client, and that row
  created — so every path below reaches the rig the way a deployment
  does: by the row's prefix, through the row's one client.

  The engine-ref is the knot the wiring needs: `:services` is built
  before the engine is, and the dispatcher the doors read must carry
  the engine. It is the same knot workqueue10.main ties at its boot."
  [st]
  (let [rig (fake-rig st)
        eng-ref (atom nil)
        eng (engine/engine
             {:storage (memory/storage)
              ;; the mcp_server kind is core's and enrols always
              ;; (modules.clj), so the row below needs no declaration
              ;; here
              :resources (conj (vec (main/resources)) caps/capability ask)
              :oidc {:issuer issuer :audience audience :jwks jwks
                     :app-url "https://app.test/"
                     :delegate-clients {"connector" "Claude"}}
              :services {;; the rig, as the bench row's client: no
                         ;; process, no socket, no network here
                         :mcp-servers
                         {:client-fn (fn [row]
                                       (when (= "bench"
                                                (str (get-in row [:data :name])))
                                         rig))}
                         ;; …and the engine's own dispatcher, the seam
                         ;; the change row's doors read
                         :bench-rpc (gate/rpc-of eng-ref)}})]
    (reset! eng-ref eng)
    ;; the bench, as a row: stdio beside the engine, the four powers,
    ;; and the engine-only four in no entry (waymark-fp62.6.3.3). The
    ;; create discovers the rig's tools and the row is born live.
    (inv/create! eng :mcp_server
                 {:name "bench"
                  :transport "stdio"
                  :command "python3"
                  :args ["-m" "bench" "--stdio"]
                  :powers bench-powers
                  :note "The bench, beside the engine."}
                 {:principal person})
    eng))

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
  (get-in (mcp/message eng (mcp/door eng) (gate/rpc-of eng) session
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

(def ^:private a-harness-bill
  "One round's usage, as the harness's hook sums it off the
  transcript."
  {:input_tokens 12000 :output_tokens 3400
   :cache_read_tokens 90000 :cache_write_tokens 1500 :turns 7
   :note "One round: the seat submitted the change."})

(defn- report!
  "The harness's Stop hook, posting the bill after the run ended
  (spec-seat.md R-12.17): the seat's key in the header, the counts in
  the body, and no bearer — the session that held one is over."
  [{:keys [h]} body]
  (h {:request-method :post :uri "/api/-/sittings/close"
      :headers {"waymark-seat-key" a-key}
      :body (wire/write-json body)}))

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
      (is (= {:repo a-repository :branch "waymark/one"
              :path "docs/orientation.md"}
             (:arguments (first (calls-of (:state w) "bench__read"))))
          "the path is answered because the engine SAW the file: one
           read of the worktree, with the engine's own hand")
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
    (is (str/includes? (str (:orientation answer)) "no orientation file")
        "a rig that answers nothing made no worktree, so there is no
         document to send the seat to — the sentence says so and
         carries what submit means instead (R-6)")
    (is (nil? (:feedback answer))
        "and no feedback: a rig that made no worktree is asked what
         the submit caused by nobody (R-12.31)")
    (is (empty? (calls-of st "bench__feedback"))
        "the sit spends no call on a bench that is not there")))

(deftest a-repository-with-no-orientation-file-answers-the-sentence
  (let [st (state)
        _ (answer! st "bench__read" {:refused "path" :repo a-repository
                                     :reason "no such file"})
        eng (fresh-engine st)
        _ (a-policy! eng {})
        _ (a-change! eng {})
        _ (open-seat! eng {})
        h (engine/handler eng)
        sid (get-in (rpc h (bearer) "initialize"
                         {:protocolVersion mcp/protocol-version
                          :capabilities {}
                          :clientInfo {:name "routine" :version "0"}})
                    [:headers "Mcp-Session-Id"])
        answer (doc-of (call! h sid "waymark_sit" {:key a-key}))]
    (is (= (str "This repository has no orientation file. Submit means: "
                (:submit_means answer))
           (:orientation answer))
        "a path to a document that is not there is a call the seat
         spends and a refusal it has to reason about; one sentence
         says it, and says what submit means here")
    (is (= 1 (count (calls-of st "bench__read")))
        "and it costs ONE read of the rig")))

;; ── the feedback, in the sit (bead waymark-fp62.6.3.9, R-12.31) ─────
;;
;; A SEAT THAT SUBMITS AND IS THEN BLIND is the failure this half
;; exists to prevent: the checks go red, a reviewer asks for a change,
;; and the next sitting knows neither. The rig already gathers both.
;; The engine asks it once, with its own hand, for a change that HAS a
;; submit behind it — a person's own pull request branch, or a round
;; this house already pushed — and carries the answer as it came.

(deftest a-change-on-a-pull-request-branch-reads-what-its-submit-caused
  (let [w (world)
        answer (:answer w)
        feedback (:feedback answer)
        call (first (calls-of (:state w) "bench__feedback"))]
    (is (= 1 (count (calls-of (:state w) "bench__feedback")))
        "ONE call for one firing, with the engine's own hand")
    (is (= {:repo a-repository :branch "waymark/one" :log_bytes 2048}
           (:arguments call))
        "the worktree the prepare made, and the tail of a failed step
         the seat can act on")

    (testing "the pull request the branch opened"
      (is (= 31 (get-in feedback [:pull_request :number])))
      (is (= "open" (get-in feedback [:pull_request :state])))
      (is (= "https://github.com/ckopsa/waymark/pull/31"
             (get-in feedback [:pull_request :url]))))

    (testing "and one finding for each thing the rig found, in the
              rig's own order: the engine adds nothing and orders
              nothing"
      (is (= 2 (count (:findings feedback))))
      (is (= {:source "pipeline" :severity "error"
              :message "FAIL in waymark10.narrow-power-test"
              :locations [{:path "waymark10/test/waymark10/narrow_power_test.clj"
                           :line 231}]}
             (first (:findings feedback)))
          "the source, the severity, the message and the locations")
      (is (= {:source "review" :severity "comment"
              :message "Name the rule in the comment."}
             (second (:findings feedback)))
          "and a finding the rig gave no locations carries none"))

    (testing "beside what the rig could not reach"
      (is (= ["statuses: the forge answered 403"] (:unavailable feedback))
          "a forge half-dark is a sentence the seat reads, not a
           refusal it has to reason about"))))

(deftest a-change-nobody-has-submitted-is-asked-nothing
  (let [w (world {} {:head_branch nil})
        answer (:answer w)]
    (is (nil? (:feedback answer))
        "a branch this house has not pushed has no pull request and no
         pipeline, so there is nothing for the rig to read")
    (is (empty? (calls-of (:state w) "bench__feedback"))
        "and the sit spends no call finding that out")
    (is (some? (:bench answer))
        "the worktree is still made: the seat works, it has just not
         submitted yet")))

(deftest a-change-with-a-round-behind-it-is-asked-even-with-no-head-branch
  (let [st (state)
        eng (fresh-engine st)
        _ (a-policy! eng {})
        change (a-change! eng {:head_branch nil})
        ;; the rig answers the branch it was asked for: the pattern's,
        ;; with the change's own id in it. The scripted default names
        ;; a person's branch, and the engine reads the feedback on the
        ;; branch the prepare ANSWERED, never on the one it guessed
        _ (answer! st "bench__prepare"
                   (assoc (get-in @st [:answers "bench__prepare"])
                          :branch (str "waymark/" (:id change))))
        ;; one round already pushed — the maintenance write
        ;; `bump-counter!` makes, one kind over
        _ (let [storage (:storage eng)
                id (str (:id change))]
            (store/with-tx storage
              (fn [tx]
                (let [row (store/load-row storage tx :change id {})]
                  (store/update-data! storage tx :change id
                                      (assoc (:data row) :rounds 1) nil)))))
        _ (open-seat! eng {})
        h (engine/handler eng)
        sid (get-in (rpc h (bearer) "initialize"
                         {:protocolVersion mcp/protocol-version
                          :capabilities {}
                          :clientInfo {:name "routine" :version "0"}})
                    [:headers "Mcp-Session-Id"])
        answer (doc-of (call! h sid "waymark_sit" {:key a-key}))
        call (first (calls-of st "bench__feedback"))
        prepare (first (calls-of st "bench__prepare"))]
    (is (= 1 (count (calls-of st "bench__feedback")))
        "this house pushed that branch once, so the checks on it are
         this seat's to read — a change names its own submit either by
         a person's head branch or by a round")
    (is (= (str "waymark/" (:id change)) (:branch (:arguments prepare)))
        "the worktree was asked for on the branch the policy's pattern
         made, which is the branch the round pushed")
    (is (= (str "waymark/" (:id change)) (:branch (:arguments call)))
        "and the feedback is read on the branch the prepare answered")
    (is (= 31 (get-in answer [:feedback :pull_request :number]))
        "and the answer carries what the rig said about it")))

(deftest a-rig-that-refuses-the-feedback-costs-the-key-and-never-the-sit
  (let [st (state)
        _ (answer! st "bench__feedback"
                   {:refused "unknown_repo" :repo a-repository
                    :reason "the bench does not hold this repository"})
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
        sat (call! h sid "waymark_sit" {:key a-key})
        answer (doc-of sat)]
    (is (false? (:isError sat)) (text-of sat))
    (is (nil? (:feedback answer))
        "a refusal is not a reading of what the submit caused, so the
         key is absent rather than holding one")
    (is (some? (:bench answer))
        "and the worktree still rides: the seat works either way")
    (is (= [(str (:id change))] (mapv :id (get-in answer [:walk :rows]))))))

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

    (testing "and the sitting is STILL OPEN (bead waymark-fp62.6.3.4)"
      (is (= :open (:state (sitting-of w)))
          "the submit ends the round on the change; the harness closes
           the sitting, and a fired run raises its one Stop event AFTER
           the submit (spec-seat.md R-12.17)")
      (is (= 0 (long (or (get-in (sitting-of w) [:data :input_tokens]) 0)))
          "and no bill is written here — the hook's report carries it"))

    (testing "and the harness's report, posted after the submit, lands"
      (let [resp (report! w a-harness-bill)
            doc (json resp)
            closed (sitting-of w)]
        (is (= 200 (:status resp)) (str (:body resp)))
        (is (= :closed (:state closed))
            "the harness is what closes the sitting")
        (is (= [12000 3400 90000 1500 7]
               [(long (get-in closed [:data :input_tokens]))
                (long (get-in closed [:data :output_tokens]))
                (long (get-in closed [:data :cache_read_tokens]))
                (long (get-in closed [:data :cache_write_tokens]))
                (long (get-in closed [:data :turns]))])
            "the counts of the submit round are on the row")
        (is (= "One round: the seat submitted the change."
               (get-in closed [:data :note])))
        (is (pos? (:cost_usd doc))
            "so a fired submit round shows its tokens and its cost on
             the ledger")))))

;; ── the title of the pull request (bead waymark-fp62.6.3.13) ────────

(def ^:private a-long-title
  "A title of more than 72 characters, as the mirror reads one off a
  pull request a person opened."
  (str "Reword the stale :spelled-by-hand waiver in grants.clj and "
       "feed_recipe.clj, and say why a form cannot lift it"))

(def ^:private a-long-sentence
  "The seat's own sentence: the whole story of the round, which is
  what made PR #160's title unreadable when the rig cut the commit's
  first line at 200 characters."
  (str "Reworded the stale :spelled-by-hand waiver (grants.clj twice, "
       "the feed_recipe.clj comment and decision_sugar_test.clj twice) "
       "from the old wording to the new one, because a form cannot "
       "lift the waiver on its own."))

(deftest the-pull-requests-title-is-the-changes-own-and-the-sentence-is-the-body
  (let [w (world)
        _ (submit! w {:why a-long-sentence})
        args (:arguments (first (calls-of (:state w) "bench__submit")))]
    (testing "the engine titles the pull request from the ROW"
      (is (= "6.3 The bench" (:title args))
          "the change's own title, and never the seat's sentence"))
    (testing "and the seat's sentence is the commit message and the body"
      (is (= a-long-sentence (:message args)))
      (is (= a-long-sentence (:description args))))))

(deftest a-title-longer-than-the-ceiling-is-cut-at-seventy-two-characters
  (let [w (world {} {:title a-long-title})
        _ (submit! w {:why "Fix the fixture's table list."})
        args (:arguments (first (calls-of (:state w) "bench__submit")))]
    (is (< 72 (count a-long-title)) "the fixture is long enough to cut")
    (is (= 72 (count (:title args)))
        "a pull request title is a label, so the engine cuts it")
    (is (= (subs a-long-title 0 72) (:title args))
        "and the cut keeps the front of the title")))

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

;; ── the enrolment (bead waymark-fp62.6.3.8) ─────────────────────────
;;
;; ONE SENTENCE ABOUT A REPOSITORY. A person writes the repo_policy
;; row; the engine tells the rig. These tests read the row the engine
;; wrote AND the call the rig heard, because a row that says it is
;; enrolled and a rig that never heard of the repository is the one
;; failure this half exists to prevent.

(defn- policy-row
  "The policy row as stored."
  [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :repo_policy (str id) {}))))

(defn- actions-of
  "Every action the transition log carries for one row, by name."
  [eng kind id]
  (into #{}
        (map #(name (:action %)))
        (store/with-tx (:storage eng)
          (fn [tx] (store/transitions (:storage eng) tx
                                      {:kind kind :resource-id (str id)}
                                      {:limit 20})))))

(deftest a-policy-create-enrols-the-repository-with-the-bench
  (let [st (state)
        eng (fresh-engine st)
        row (a-policy! eng {:clone_url "https://git.example/waymark.git"})
        call (first (calls-of st "bench__enroll"))
        stored (policy-row eng (:id row))]
    (is (= {:repo a-repository
            :clone_url "https://git.example/waymark.git"
            :default_branch "main"
            :deny ["*.env"]
            :land {:target "main" :rebase false :stages []
                   :pull_request true}}
           (:arguments call))
        "the whole sentence the rig needs: the name it holds the clone
         under, where to clone it from, which branch a worktree starts
         from, the paths it never serves, and what a submit LANDS —
         without the land block the rig only pushes, and the branch
         gets no pull request at all (bead waymark-fp62.6.3.9)")
    (is (false? (get-in (:arguments call) [:land :rebase]))
        "a seat may work on a person's own pull request branch, and a
         rebase there rewrites a person's history")
    (is (some? (get-in stored [:data :enrolled_at]))
        "and the row says the bench holds this repository now")
    (is (nil? (get-in stored [:data :note]))
        "with nothing to explain")))

(deftest a-delegate-states-the-policy-and-a-model-alone-does-not
  ;; The owner's ruling, 2026-09-19: a person works with a model to add
  ;; a repository, so the wall is against a model ALONE. This lives in
  ;; the suite and not beside the kind's scenarios because a check-tier
  ;; actor carries id, roles and type, and never acts-for.
  (let [st (state)
        eng (fresh-engine st)
        row (a-policy! eng {})
        delegate (assoc (t/principal {:id "claude-for-colton" :type :agent
                                      :display "Claude for Colton"})
                        :acts-for "colton")
        ;; the restate is fenced: it wants the row's own etag, the
        ;; shape the wire spells it in
        restated (fn [who lines]
                   (let [current (policy-row eng (:id row))]
                     (inv/invoke! eng :repo_policy (str (:id row)) :restate
                                  (assoc (select-keys (:data current)
                                                      [:repository :branch_pattern :base
                                                       :max_lines :opens_pr :auto_merge
                                                       :rounds_per_change :formatter
                                                       :deny :orientation])
                                         :max_lines lines)
                                  {:principal who
                                   :if-match (inv/etag :repo_policy (:id row)
                                                       (:version current))})))]
    (testing "an agent that acts for a person is the person's hand"
      (restated delegate 600)
      (is (= 600 (get-in (policy-row eng (:id row)) [:data :max_lines]))
          "the grant it wears is the person's decision, and the row moved"))
    (testing "an agent that acts for nobody is a model alone, and the wall stands"
      (is (thrown? clojure.lang.ExceptionInfo (restated clerk 4000)))
      (is (= 600 (get-in (policy-row eng (:id row)) [:data :max_lines]))
          "a seat's sitter could otherwise raise its own ceiling"))))

(deftest a-policy-that-opens-no-pull-request-lands-without-one
  (let [st (state)
        eng (fresh-engine st)
        _ (a-policy! eng {:opens_pr false :base "dev"})
        land (:land (:arguments (first (calls-of st "bench__enroll"))))]
    (is (= {:target "dev" :rebase false :stages []} land)
        "the policy says the push opens no pull request, so the block
         carries none and the rig pushes the branch and stops")))

(deftest a-policy-that-names-no-clone-url-is-cloned-from-github
  (let [st (state)
        eng (fresh-engine st)
        _ (a-policy! eng {})
        call (first (calls-of st "bench__enroll"))]
    (is (= (str "https://github.com/" a-repository) (:clone_url (:arguments call)))
        "a repository spelled as owner/repo needs no URL from a
         person: the engine knows where GitHub keeps it")))

(deftest a-rig-that-refuses-the-enrolment-leaves-the-note-and-the-retry-lands-it
  (let [st (state)
        _ (answer! st "bench__enroll"
                   {:refused "clone_failed" :repo a-repository
                    :reason "the remote answered 404"})
        eng (fresh-engine st)
        row (a-policy! eng {})
        id (str (:id row))
        stored (policy-row eng id)]
    (is (= "active" (name (:state stored)))
        "the person's sentence stands whatever the rig says: a policy
         is not refused by a bench")
    (is (nil? (get-in stored [:data :enrolled_at])))
    (is (= (str bench/not-enrolled-prefix "the remote answered 404")
           (get-in stored [:data :note]))
        "and the row says why, in the rig's own words")

    (testing "the retry offers it again, and the rig takes it"
      (answer! st "bench__enroll"
               {:name a-repository :clone_url "https://github.com/ckopsa/waymark"
                :default_branch "main" :deny [] :land "worktree"
                :bare true :cloned true})
      (bench/enroll-unenrolled! eng)
      (let [after (policy-row eng id)]
        (is (some? (get-in after [:data :enrolled_at])))
        (is (nil? (get-in after [:data :note])))
        (is (= 2 (count (calls-of st "bench__enroll")))
            "one call at the birth and one at the retry")
        (is (contains? (actions-of eng :repo_policy id) "mark_enrolled")
            "through the row's own hidden door, so the transition log
             carries the enrolment rather than a silent field write")))

    (testing "and a second pass offers nothing: the stamp is the memory"
      (bench/enroll-unenrolled! eng)
      (is (= 2 (count (calls-of st "bench__enroll")))))))

(deftest a-retire-unenrols-the-repository-and-a-restore-enrols-it-again
  (let [st (state)
        eng (fresh-engine st)
        row (a-policy! eng {})
        id (str (:id row))]
    (inv/invoke! eng :repo_policy id :retire {} {:principal person})
    (is (= {:repo a-repository} (:arguments (first (calls-of st "bench__unenroll"))))
        "the rig is told to stop holding this repository")
    (let [stored (policy-row eng id)]
      (is (= "retired" (name (:state stored))))
      (is (nil? (get-in stored [:data :enrolled_at]))
          "and the row no longer says the bench holds it"))

    (testing "a restore enrols it again, as the create did"
      (inv/invoke! eng :repo_policy id :restore {} {:principal person})
      (is (= 2 (count (calls-of st "bench__enroll"))))
      (is (some? (get-in (policy-row eng id) [:data :enrolled_at]))))))

(deftest a-rig-that-refuses-the-unenrolment-retires-the-row-anyway
  (let [st (state)
        eng (fresh-engine st)
        row (a-policy! eng {})
        id (str (:id row))
        _ (answer! st "bench__unenroll"
                   {:refused "config_repo" :repo a-repository
                    :reason "the repository is named in the rig's own configuration"})
        _ (inv/invoke! eng :repo_policy id :retire {} {:principal person})
        stored (policy-row eng id)]
    (is (= "retired" (name (:state stored)))
        "a person who retires a policy has retired it")
    (is (= (str bench/not-unenrolled-prefix
                "the repository is named in the rig's own configuration")
           (get-in stored [:data :note]))
        "and the refusal is noted, not raised")))

(deftest the-enrolment-tools-are-on-no-power-and-the-engine-reaches-them-anyway
  (let [w (power-world ["bench.read" "bench.find" "bench.edit" "bench.pull"])]
    (doseq [tool ["bench__enroll" "bench__repos" "bench__unenroll"]]
      (let [r (tool! w "waymark_power" {:tool tool :arguments {}})]
        (is (true? (:isError r))
            (str tool " is on no powers entry, so no scope reaches it"))))
    (is (empty? (calls-of (:state w) "bench__enroll"))
        "and the power door asked the rig nothing")

    (testing "…while the engine's own hand reaches the same tool"
      (let [answer (bench/ask {:services {:bench-rpc (gate/rpc-of (:eng w))}}
                              :repos {})]
        (is (= ["ckopsa/waymark"] (:repos answer))
            "mcp-servers/call! resolves bench__repos by the ROW's name
             and rides the row's one client: the powers decide what a
             GRANT may reach, and the engine wears no grant")))))


;; ── the seat that builds an ask (bead waymark-fp62.6.3.10, R-12.32) ─
;;
;; A CODE SEAT MUST BUILD WHAT A PERSON ASKS FOR. The queue is a list
;; of asks, not a list of pull requests, so the first row names no
;; repository and there is no change to submit. This half proves the
;; three things the engine does about that: it reads the repository
;; off the seat's own scope, it mints ONE change row for the ask, and
;; it answers that row beside the walk with the doors the seat holds.

(def ^:private ask-scope
  "The scope of a seat that builds asks: the queue it walks, the
  change doors it submits with, and the bench powers — each one
  filtered to the one repository, which is where the engine reads the
  repository from (R-12.32)."
  (into [{:kind "ask" :actions ["complete"]}
         {:kind "change" :actions ["submit" "stall" "discard"]}]
        (map (fn [token] {:kind token :actions []
                          :filter {:repo a-repository}}))
        ["bench.find" "bench.read" "bench.edit" "bench.pull"]))

(defn- an-ask!
  "One row of the queue a person writes."
  [eng title]
  (:row (inv/create! eng :ask {:title title :status "open"}
                     {:principal person})))

(defn- changes-of
  "Every change row in the store."
  [eng]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx :change {} {:limit 20}))))

(defn- ask-world
  "An engine with the policy, one ask, a seat that WALKS asks, and a
  session sat in it. `scope` is the seat's, so a test can give it two
  repositories or none."
  ([] (ask-world ask-scope))
  ([scope]
   (let [st (state)
         eng (fresh-engine st)
         _ (a-policy! eng {})
         asked (an-ask! eng "Put the size ceiling on the policy form")
         seat (open-seat! eng {:scope scope :walk "ask"})
         h (engine/handler eng)
         sid (get-in (rpc h (bearer) "initialize"
                          {:protocolVersion mcp/protocol-version
                           :capabilities {}
                           :clientInfo {:name "routine" :version "0"}})
                     [:headers "Mcp-Session-Id"])
         sat (call! h sid "waymark_sit" {:key a-key})]
     {:eng eng :state st :h h :sid sid :seat seat :ask asked
      :sat sat :answer (doc-of sat)})))

(deftest a-seat-that-walks-asks-is-created-and-mints-one-change-for-the-first-row
  (let [w (ask-world)
        answer (:answer w)
        ask-id (str (:id (:ask w)))
        branch (str "waymark/" ask-id)]
    (is (false? (:isError (:sat w))) (text-of (:sat w)))
    (is (= :active (:state (:seat w)))
        "a seat walking a kind whose default filter names `status` and
         not `state` is created with no refusal at all")

    (testing "the walk is the asks"
      (is (= "ask" (get-in answer [:walk :kind])))
      (is (= [ask-id] (mapv :id (get-in answer [:walk :rows])))))

    (testing "and one change row was minted for the first ask"
      (let [rows (changes-of (:eng w))]
        (is (= 1 (count rows)) "one row, not one for each sitting")
        (let [d (:data (first rows))]
          (is (= (str "ask:" ask-id) (:change_id d))
              "the walk kind, a colon and the walk row's own id — so a
               second sitting on the same ask finds this row")
          (is (= (str "ask:" ask-id) (:born_from d))
              "the same words again, in a field the adoption does not
               touch (bead waymark-fp62.6.3.14): `change_id` becomes
               GitHub's when the push opens the pull request, and the
               merge must still know which row this change was built
               for")
          (is (= a-repository (:repository d))
              "read off the seat's own bench powers, because an ask
               names no repository")
          (is (= "Put the size ceiling on the policy form" (:title d))
              "the ask's own words, so a person reads one story in the
               queue and on the pull request")
          (is (= branch (:head_branch d))
              "the policy's pattern with the ASK's id in place of the
               star: the branch says which ask it builds")
          (is (= "main" (:base_branch d)))
          (is (= "bench-seat" (:author d)))
          (is (nil? (:number d))
              "nothing has opened a pull request yet, and a number
               nobody has been given is not invented here"))))

    (testing "the change rides beside the walk, with the seat's doors on it"
      (is (= "change" (get-in answer [:change :kind])))
      (is (= (str (:id (first (changes-of (:eng w)))))
             (get-in answer [:change :id])))
      (is (= #{"submit" "stall" "discard"}
             (into #{} (map :action) (get-in answer [:change :doors])))
          "read AS THE SITTER, so the doors are the seat's scope and
           not the mirror's")
      (is (some #(= "submit" (:action %)) (get-in answer [:change :doors]))
          "the door a round ends with is in the answer the sit gives"))

    (testing "and the bench is the one that change names"
      (is (= branch (:branch (:arguments (first (calls-of (:state w)
                                                          "bench__prepare")))))
          "the worktree is asked for on the ask's own branch")
      (is (some? (:bench answer)))
      (is (= "docs/orientation.md" (:orientation answer)))
      (is (str/includes? (:submit_means answer) "pull request")))

    (testing "and the bench names the TOOL for each bench power the
              seat holds (bead waymark-fp62.6.3.12)"
      (is (= {:bench.edit "bench__edit"
              :bench.find "bench__find"
              :bench.pull "bench__pull"
              :bench.read "bench__read"}
             (get-in answer [:bench :tools]))
          "the seat's scope gives the tokens and the bench row's
           powers give the tool, so the sit says what to call and no
           instruction has to name a spelling"))

    (testing "and nothing is asked about a submit that has not happened"
      (is (nil? (:feedback answer)))
      (is (empty? (calls-of (:state w) "bench__feedback"))
          "a branch this house minted and never pushed has no pull
           request and no pipeline to read"))))

(deftest the-bench-names-the-tools-of-the-powers-the-scope-holds-and-no-others
  (let [w (ask-world [{:kind "ask" :actions ["complete"]}
                      {:kind "change" :actions ["submit" "stall" "discard"]}
                      {:kind "bench.read" :actions []
                       :filter {:repo a-repository}}])
        answer (:answer w)]
    (is (false? (:isError (:sat w))) (text-of (:sat w)))
    (is (= {:bench.read "bench__read"} (get-in answer [:bench :tools]))
        "a power the scope does not name is ABSENT: the seat is told
         what it may call and nothing else")))

(deftest a-second-sitting-on-the-same-ask-finds-the-first-sittings-change
  (let [w (ask-world)
        first-id (get-in (:answer w) [:change :id])
        again (doc-of (call! (:h w) (:sid w) "waymark_sit" {:key a-key}))]
    (is (= first-id (get-in again [:change :id]))
        "change_id is :unique and the sit asks for it before it mints,
         so the second firing works the row the first one made")
    (is (= 1 (count (changes-of (:eng w))))
        "one ask is one change, however many times a seat sits down to
         it")))

(deftest a-seat-born-changes-pull-request-is-titled-with-the-asks-own-title
  (let [w (ask-world)
        change-id (get-in (:answer w) [:change :id])
        r (call! (:h w) (:sid w) "waymark_invoke"
                 {:kind "change" :id change-id :action "submit"
                  :input {:why a-long-sentence}})
        args (:arguments (first (calls-of (:state w) "bench__submit")))]
    (is (false? (:isError r)) (text-of r))
    (is (= "Put the size ceiling on the policy form" (:title args))
        "the task's own title, so a person reads one story in the
         queue and on the pull request (bead waymark-fp62.6.3.13)")
    (is (= a-long-sentence (:description args))
        "and the seat's sentence is the body of the pull request")
    (is (= a-long-sentence (:message args))
        "which is the commit message as well")))

(deftest a-scope-that-does-not-name-one-repository-gives-the-rows-and-a-note
  (testing "two repositories are not one"
    (let [w (ask-world [{:kind "ask" :actions ["complete"]}
                        {:kind "change" :actions ["submit" "stall" "discard"]}
                        {:kind "bench.read" :actions []
                         :filter {:repo a-repository}}
                        {:kind "bench.edit" :actions []
                         :filter {:repo "ckopsa/other"}}])
          answer (:answer w)]
      (is (false? (:isError (:sat w))) (text-of (:sat w)))
      (is (nil? (:bench answer)))
      (is (str/includes? (str (:bench_note answer)) "one repository")
          "the sentence is about the SEAT, and it names what a person
           writes in the scope")
      (is (= [(str (:id (:ask w)))] (mapv :id (get-in answer [:walk :rows])))
          "the rows still ride: a sitting that cannot reach the bench
           can read its queue and say so")
      (is (empty? (changes-of (:eng w)))
          "and no change is minted against a repository nobody named")
      (is (empty? (calls-of (:state w) "bench__prepare"))
          "the rig is asked for nothing")))
  (testing "and an unfiltered bench entry names none"
    (let [w (ask-world [{:kind "ask" :actions ["complete"]}
                        {:kind "change" :actions ["submit" "stall" "discard"]}
                        {:kind "bench.read" :actions []}])
          answer (:answer w)]
      (is (nil? (:bench answer)))
      (is (str/includes? (str (:bench_note answer)) "one repository")))))

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
