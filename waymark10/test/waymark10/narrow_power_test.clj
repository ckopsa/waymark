(ns waymark10.narrow-power-test
  "The NARROW POWER (bead waymark-fp62.6.3.5): a bench grant that
  names one repository, or one set of paths, instead of the whole rig.

  THE PROBLEM. A bench power was the whole rig: a grant naming
  bench.read admitted every repository the rig held and every file in
  each one. The grant machinery could already carry a narrower
  sentence as a `filter` on a scope entry, and the power door refused
  every filtered entry rather than interpret one.

  THE OWNER'S RULING, which this file is the proof of: the ENGINE is
  the enforcement point for the bench and the rig is the hand. The
  seat key stays in the engine, and the rig holds no seat and no rule
  between calls — so the narrowing is a sentence the grant carries and
  the door reads on every call.

  THE TWO HALVES.

  • The mcp_server row says which FIELDS a filter may name for each
    power (`constraints`). The scope guard judges a filtered entry
    against them at the ASK, so a person is never asked to approve a
    narrowing the door would not know how to honour, and a power that
    names no constraints (Gate's, all of them) refuses every filter
    before a tap.

  • The power door judges each CALL against the filter before the
    forward: a `repo` must be one the filter names, a `path` must
    match one of its globs, a call that names no path at all is
    forwarded with the globs as `allow`, and a call outside every
    entry refuses 403 without touching the rig.

  Beside them rides the office (R-5): a bench call made in a bound
  sitting carries `seat` and `sitting`, so the rig's own record of who
  touched the checkout is the office and not the model.

  Memory storage, a held clock, an in-process fake rig and an
  in-process fake Gate: no network, no database, real rows through the
  engine. The fixture shapes are factory10.bench-test's and
  waymark10.mcp-power-shape-test's."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private clock (Instant/parse "2026-09-18T09:00:00Z"))

(def ^:private a-repo "ckopsa/waymark")
(def ^:private another-repo "ckopsa/waymark-bench")

(def ^:private model-name "narrow-power-model")

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private clerk
  (t/principal {:id "bench-clerk" :type :agent :display "Bench clerk"
                :model model-name}))

(def ^:private rig-tools
  "What the fake rig's tools/list serves: its own BARE names, because
  the `bench__` prefix is the row's name and the engine puts it on.
  `prepare` is named by no powers entry — the engine's own hand, on no
  token, and no scope can reach it. `feedback` is on a token AND on
  the engine's own hand, as `read` is."
  (mapv (fn [nm]
          {:name nm
           :description (str "The bench's " nm ".")
           :inputSchema {:type "object"
                         :properties {:repo {:type "string"}
                                      :path {:type "string"}}}})
        ["prepare" "find" "read" "edit" "edit_many" "pull" "feedback"]))

(def ^:private bench-powers
  "The bench row's policy, with the CONSTRAINTS this bead adds: find,
  read, edit and edit_many may be narrowed by the repository and by
  the path;
  pull moves a whole checkout and feedback reads a whole branch, so a
  path could not mean anything on either and only the repository may
  narrow them. `bench.feedback` is the fifth power (bead
  waymark-fp62.6.3.9): the sit already carries what a submit caused,
  and this is the seat asking the rig again itself."
  [{:power "bench.find" :tools ["find"] :why false
    :constraints ["repo" "path"]}
   {:power "bench.read" :tools ["read"] :why false
    :constraints ["repo" "path"]}
   {:power "bench.edit" :tools ["edit"] :why false
    :constraints ["repo" "path"]}
   {:power "bench.edit_many" :tools ["edit_many"] :why false
    :constraints ["repo" "path"]}
   {:power "bench.pull" :tools ["pull"] :why false
    :constraints ["repo"]}
   {:power "bench.feedback" :tools ["feedback"] :why false
    :constraints ["repo"]}
   ;; a power of TWO tools, for bead waymark-fp62.6.3.12: it names no
   ;; single tool, so it is not a tool name and the door says so. It
   ;; is LAST because `entry-for` takes the first entry that names a
   ;; tool, and `find` and `read` are their own powers above.
   {:power "bench.look" :tools ["find" "read"] :why false
    :constraints ["repo"]}])

(defn- fake-rig
  "The rig — (fn [method params]), the shape an mcp_server row's client
  answers — recording every tools/call it is asked on `log`, under the
  PREFIXED name the callers below spell. The log IS the wire: a call
  here reached the rig, and 'nothing reached the rig' is its absence."
  [log]
  (fn [method params]
    (case method
      "tools/list" {:tools rig-tools}
      "tools/call"
      (do (swap! log conj {:tool (str "bench__" (:name params))
                           :arguments (:arguments params)})
          {:isError false
           :content [{:type "text"
                      :text (wire/write-json {:ok (str (:name params))})}]})
      (throw (ex-info (str "the fake rig speaks no " method) {})))))

(defn- fake-gate
  "The gate row's client: it answers one tool list and nothing here
  ever calls it. Gate is in this namespace for one sentence only — its
  powers entries name no `constraints`, so no grant may filter one."
  []
  (fn [method _params]
    (case method
      "tools/list" {:tools [{:name "tgram__send_message"
                             :description "Send a telegram message."
                             :inputSchema {:type "object" :properties {}}}]}
      {})))

(def ^:private ticket-kind
  "A walk row that carries its own descriptive branch (ticket 81931c1c)."
  (r/resource
   {:kind :ticket
    :plural "tickets"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "What it asks"}}
      [:string {:min 1 :max 120}]]
     [:branch {:x-display {:label "Its branch"}}
      [:string {:min 1 :max 120}]]]
    :filterable {:state #{:eq :in}}
    :sortable {:fields [:title] :default "title"}
    :actions
    {:close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A test kind: nothing reopens it."}
             :display {:label "Close" :style :primary :order 1}}}}))

(defn- fresh-engine
  "An engine over the two fakes: the rig is the `bench` row's client
  through the `:client-fn` seam, and Gate is the bridge row's through
  `:gate-rpc`. The capability registry rides along as the app declares
  it, because a dotted scope entry no row names is judged against it."
  [log]
  (let [rig (fake-rig log)]
    (doto (engine/engine
           {:storage (memory/storage)
            :resources [caps/capability ticket-kind]
            :now-fn (fn [] clock)
            :services {:mcp-servers
                       {:client-fn (fn [row]
                                     (when (= "bench"
                                              (str (get-in row [:data :name])))
                                       rig))
                        :gate-rpc (fake-gate)}}})
      (gate/ensure-gate-row!))))

(defn- a-bench-row!
  "The bench, as a row: stdio beside the engine, the powers with
  their constraints, and `prepare` in no entry at all."
  [eng]
  (:row (inv/create! eng :mcp_server
                     {:name "bench"
                      :transport "stdio"
                      :command "python3"
                      :args ["-m" "bench" "--stdio"]
                      :powers bench-powers
                      :note "The bench, beside the engine."}
                     {:principal colton})))

(defn- wear!
  "A grant naming `scope`, minted by a person and accepted by the
  clerk — the ordinary machinery. → the visibility it confers."
  [eng scope]
  (let [row (:row (inv/create! eng :grant
                               {:audience (:id clerk) :scope scope}
                               {:principal colton}))]
    (inv/invoke! eng :grant (:id row) :accept {} {:principal clerk})
    {:id (str (:id row))
     :visibility (grants/visibility eng (str (:id row)) clerk)}))

(defn- world
  "One engine with the bench row and the gate row, and the clerk
  wearing a grant over `scope`. → {:eng :log :session :grant}."
  ([] (world [{:kind "capability" :actions []}]))
  ([scope]
   (let [log (atom [])
         eng (fresh-engine log)
         _ (a-bench-row! eng)
         worn (wear! eng scope)]
     {:eng eng
      :log log
      :grant (:id worn)
      :session {:principal clerk :visibility (:visibility worn)}})))

(defn- refusal
  "The problem ex-data of a write that was refused, or nil when it was
  served — {:guard … :detail … :status …}."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(defn- ask!
  "One approval_request, filed by the clerk. It is the door the design
  names: a narrowing a person would be asked to approve."
  [eng scope]
  (:row (inv/create! eng :approval_request
                     {:task "Read the code and say what is wrong."
                      :scope scope}
                     {:principal clerk})))

(defn- power!
  "One `waymark_power` call through the whole message layer — the path
  the transport drives, so the byte counter and the stamp run too."
  [{:keys [eng session]} args]
  (get-in (mcp/message eng (mcp/door eng) (gate/rpc-of eng) session
                       {:jsonrpc "2.0" :id 1 :method "tools/call"
                        :params {:name "waymark_power" :arguments args}})
          [:result]))

(defn- text-of [result]
  (str/join " " (keep :text (:content result))))

(defn- calls [{:keys [log]}] @log)

(defn- last-arguments [{:keys [log]}]
  (:arguments (last @log)))

;; ── acceptance 1: the row says which fields a filter may name ───────

(deftest an-ask-narrows-a-power-only-by-a-field-the-row-names
  (let [w (world)
        eng (:eng w)]
    (testing "`branch` is not one of bench.read's constraints, so the
              ask refuses — before a person is ever asked to tap it"
      (let [p (refusal #(ask! eng [{:kind "bench.read" :actions []
                                    :filter {:branch "waymark/one"}}]))]
        (is (= :scope-filters-are-filterable (:guard p)) (pr-str p))
        (is (str/includes? (str (:detail p)) "branch")
            "the refusal spells the field that failed")
        (is (str/includes? (str (:detail p)) "bench.read")
            "…and the power it failed on")))

    (testing "`repo` is one of them, so the same ask stands"
      (let [row (ask! eng [{:kind "bench.read" :actions []
                            :filter {:repo a-repo}}])]
        (is (= {:repo a-repo} (:filter (first (get-in row [:data :scope]))))
            "the narrowing landed as written, for a person to read")))))

;; ── acceptance 2: the repository the filter names, and no other ─────

(deftest a-repo-filter-forwards-its-own-repository-and-refuses-the-rest
  (let [w (world [{:kind "bench.read" :actions [] :filter {:repo a-repo}}])]

    (testing "a read inside the filter forwards, and the rig hears it"
      (let [r (power! w {:tool "bench__read"
                         :arguments {:repo a-repo :path "src/a.clj"}})]
        (is (false? (:isError r)) (text-of r))
        (is (= 1 (count (calls w))))
        (is (= a-repo (:repo (last-arguments w))))))

    (testing "a read on ANOTHER repository refuses 403, names the
              filter, and never reaches the rig"
      (let [r (power! w {:tool "bench__read"
                         :arguments {:repo another-repo :path "src/a.clj"}})
            said (text-of r)]
        (is (true? (:isError r)) said)
        (is (str/includes? said "bench.read") "the token, to ask again for")
        (is (str/includes? said "repo") "the field that failed")
        (is (str/includes? said another-repo) "what this call carried")
        (is (str/includes? said a-repo) "and what the filter admits")
        (is (= 1 (count (calls w)))
            "nothing reached the rig: the refusal is in-process")))))

;; ── acceptance 3: the paths the filter names, and the `allow` ───────

(deftest a-path-filter-judges-a-named-path-and-hands-the-globs-to-a-find
  (let [globs "docs/**,README.md"
        w (world [{:kind "bench.read" :actions [] :filter {:path globs}}
                  {:kind "bench.find" :actions [] :filter {:path globs}}])]

    (testing "a path the globs match forwards"
      (let [r (power! w {:tool "bench__read"
                         :arguments {:repo a-repo :path "docs/x.md"}})]
        (is (false? (:isError r)) (text-of r))
        (is (nil? (:allow (last-arguments w)))
            "the call named its own path, so there is nothing to allow")))

    (testing "a path they do not match refuses, and the rig hears
              nothing new"
      (let [before (count (calls w))
            r (power! w {:tool "bench__read"
                         :arguments {:repo a-repo :path "src/a.clj"}})
            said (text-of r)]
        (is (true? (:isError r)) said)
        (is (str/includes? said "path"))
        (is (str/includes? said "src/a.clj"))
        (is (= before (count (calls w))))))

    (testing "a find that names NO path forwards, with the globs as
              `allow`: the rig holds itself to them for this one call,
              and the engine never has to walk a tree to answer"
      (let [r (power! w {:tool "bench__find" :arguments {:repo a-repo}})]
        (is (false? (:isError r)) (text-of r))
        (is (= ["docs/**" "README.md"] (:allow (last-arguments w)))
            "every glob of the filter, split on the comma")))

    (testing "and the rig's own `.` is the same whole-tree ask"
      (let [r (power! w {:tool "bench__find"
                         :arguments {:repo a-repo :path "."}})]
        (is (false? (:isError r)) (text-of r))
        (is (= ["docs/**" "README.md"] (:allow (last-arguments w))))))

    (testing "the grammar is the rig's fnmatch: `*` crosses slashes, a glob matches the last part alone, `**/` may be dropped"
      (is (true? (gate/path-glob-matches? "docs/**" "docs/a/b.md")))
      (is (true? (gate/path-glob-matches? "docs/*" "docs/a/b.md")))
      (is (true? (gate/path-glob-matches? "docs/*" "docs/b.md")))
      (is (false? (gate/path-glob-matches? "docs/*" "src/b.md")))
      (is (true? (gate/path-glob-matches? "*.pem" "keys/server.pem")))
      (is (true? (gate/path-glob-matches? "**/secrets/**" "a/secrets/b")))
      (is (true? (gate/path-glob-matches? "src/?.clj" "src/a.clj")))
      (is (false? (gate/path-glob-matches? "src/?.clj" "src/ab.clj"))))))

(deftest a-path-filter-judges-a-moves-move-to-by-the-same-globs
  (let [w (world [{:kind "bench.edit" :actions []
                   :filter {:repo a-repo :path "docs/**"}}])]

    (testing "a move within the globs forwards"
      (let [r (power! w {:tool "bench__edit"
                         :arguments {:repo a-repo :path "docs/a.md"
                                     :move_to "docs/b.md"}})]
        (is (false? (:isError r)) (text-of r))
        (is (= "docs/b.md" (:move_to (last-arguments w))))))

    (testing "a move out of the globs refuses on move_to, and the rig
              hears nothing new"
      (let [before (count (calls w))
            r (power! w {:tool "bench__edit"
                         :arguments {:repo a-repo :path "docs/a.md"
                                     :move_to "src/anything.clj"}})
            said (text-of r)]
        (is (true? (:isError r)) said)
        (is (str/includes? said "move_to"))
        (is (str/includes? said "src/anything.clj"))
        (is (= before (count (calls w))))))

    (testing "an edit with no move_to is judged by its path alone"
      (let [r (power! w {:tool "bench__edit"
                         :arguments {:repo a-repo :path "docs/a.md"}})]
        (is (false? (:isError r)) (text-of r))))))

;; ── acceptance 4: two entries on one grant, admitted by either ──────

(deftest two-filtered-entries-stand-on-one-grant-and-the-door-admits-by-either
  (let [w (world [{:kind "bench.edit" :actions []
                   :filter {:repo a-repo :path "docs/**"}}
                  {:kind "bench.edit" :actions []
                   :filter {:repo another-repo :path "src/**"}}])]

    (testing "the surface kept BOTH, where one kind's filters are kept
              to one entry"
      (is (= 2 (count (:filters (grants/capability-entry
                                 (get-in w [:session :visibility])
                                 "bench.edit"))))))

    (testing "each entry admits its own repository and paths"
      (let [a (power! w {:tool "bench__edit"
                         :arguments {:repo a-repo :path "docs/a.md"}})
            b (power! w {:tool "bench__edit"
                         :arguments {:repo another-repo :path "src/a.py"}})]
        (is (false? (:isError a)) (text-of a))
        (is (false? (:isError b)) (text-of b))
        (is (= 2 (count (calls w))))))

    (testing "and a call NEITHER admits refuses"
      (let [crossed (power! w {:tool "bench__edit"
                               :arguments {:repo a-repo :path "src/a.clj"}})
            stranger (power! w {:tool "bench__edit"
                                :arguments {:repo "someone/else"
                                            :path "docs/a.md"}})]
        (is (true? (:isError crossed))
            "the first repository with the second's paths is neither entry")
        (is (true? (:isError stranger)))
        (is (= 2 (count (calls w)))
            "two forwards in this test, and both of them were allowed")))))

;; ── a repo-only power: bench.feedback (bead waymark-fp62.6.3.9) ────

(deftest a-repo-only-power-is-narrowed-by-the-repository-and-by-nothing-else
  (let [w (world)
        eng (:eng w)]
    (testing "`path` is not one of bench.feedback's constraints, so the
              ask refuses: a feedback reads a whole branch, and a path
              could not narrow one"
      (let [p (refusal #(ask! eng [{:kind "bench.feedback" :actions []
                                    :filter {:path "docs/**"}}]))]
        (is (= :scope-filters-are-filterable (:guard p)) (pr-str p))
        (is (str/includes? (str (:detail p)) "path"))
        (is (str/includes? (str (:detail p)) "bench.feedback"))))

    (testing "`repo` is the one field it names, and the door holds the
              call to it"
      (let [w2 (world [{:kind "bench.feedback" :actions []
                        :filter {:repo a-repo}}])
            mine (power! w2 {:tool "bench__feedback"
                             :arguments {:repo a-repo :branch "waymark/one"}})]
        (is (false? (:isError mine)) (text-of mine))
        (is (nil? (:allow (last-arguments w2)))
            "the filter narrows no path, so the rig is told no globs")
        (let [theirs (power! w2 {:tool "bench__feedback"
                                 :arguments {:repo another-repo
                                             :branch "waymark/one"}})]
          (is (true? (:isError theirs)) (text-of theirs))
          (is (str/includes? (text-of theirs) "bench.feedback"))
          (is (= 1 (count (calls w2)))
              "the refusal is in-process: nothing new reached the rig"))))))

;; ── acceptance 5: a power that names no constraints takes no filter ─

(deftest a-filter-on-a-gate-power-refuses-at-the-ask
  (let [w (world)
        eng (:eng w)]
    (is (contains? (set (servers/power-tokens eng)) "telegram.send")
        "the gate row names the token, so the scope may name it")
    (testing "…and naming it with a FILTER refuses: the gate row's
              entries carry no `constraints`, so there is no field this
              door was taught to hold itself to"
      (let [p (refusal #(ask! eng [{:kind "telegram.send" :actions []
                                    :filter {:chat "family"}}]))]
        (is (= :scope-filters-are-filterable (:guard p)) (pr-str p))
        (is (str/includes? (str (:detail p)) "telegram.send"))))
    (testing "the same ask without a filter stands"
      (is (some? (ask! eng [{:kind "telegram.send" :actions []}]))))))

;; ── acceptance 6: the office rides every bench call ─────────────────

(defn- a-model! [eng]
  (:row (inv/create! eng :model
                     {:name model-name :display "Narrow Power 1"
                      :vendor "anthropic" :tier "economy"
                      :price_input_per_mtok 1M
                      :price_output_per_mtok 5M
                      :price_cache_read_per_mtok 0.1M
                      :price_cache_write_per_mtok 1.25M}
                     {:principal colton})))

(defn- a-seat! [eng]
  (:row (inv/create! eng :seat
                     {:name "narrow-power"
                      :charter "Read the code and say what is wrong."
                      :scope [{:kind "capability" :actions []}]
                      :standing_ttl_seconds 604800
                      :cadence_seconds 3600
                      :budget_usd_per_week 5M
                      :sitting_budget_tokens 1000000}
                     {:principal colton})))

(deftest a-bench-call-in-a-bound-sitting-carries-the-seat-and-the-sitting
  (let [w (world [{:kind "bench.read" :actions [] :filter {:repo a-repo}}])
        eng (:eng w)
        seat (a-seat! eng)
        model (a-model! eng)
        sitting (:row (inv/create! eng :sitting
                                   {:seat (:id seat) :model (:id model)
                                    :grant (:grant w)}
                                   {:principal clerk}))
        sid (mcp/open-session! eng)
        ;; the bind `waymark_sit` makes, made here: this file is about
        ;; what the DISPATCH does with a binding, and the sit's own
        ;; end-to-end path is pinned in factory10.bench-test
        _ (mcp/bind-session! eng sid {:seat (:id seat) :sitter clerk
                                      :sitting (:id sitting)})
        bound (assoc w :session (assoc (:session w) :mcp-session-id sid))
        r (power! bound {:tool "bench__read"
                         :arguments {:repo a-repo :path "src/a.clj"}})]
    (is (false? (:isError r)) (text-of r))
    (let [sent (last-arguments bound)]
      (is (= (str (:id seat)) (str (:seat sent)))
          "the office, so `git blame` answers the seat and not the model")
      (is (= (str (:id sitting)) (str (:sitting sent)))
          "and the sitting, so one run's writes are one run's"))

    (testing "a session with no bound sitting carries neither: the rig's
              record then says what is true — nobody is sitting"
      (let [r2 (power! w {:tool "bench__read"
                          :arguments {:repo a-repo :path "src/b.clj"}})
            sent (last-arguments w)]
        (is (false? (:isError r2)) (text-of r2))
        (is (nil? (:seat sent)))
        (is (nil? (:sitting sent)))))))

;; ── the worktree the sit prepared rides every bench call ─────────────

(def ^:private a-branch "bench/one")

(defn- bound-world
  "The world with a sitting bound to a fresh session, the binding
  carrying `bench` as the sit puts it there — nil for a sitting whose
  sit prepared no worktree."
  [bench]
  (let [w (world [{:kind "bench.read" :actions [] :filter {:repo a-repo}}
                  {:kind "bench.find" :actions [] :filter {:repo a-repo}}])
        eng (:eng w)
        seat (a-seat! eng)
        model (a-model! eng)
        sitting (:row (inv/create! eng :sitting
                                   {:seat (:id seat) :model (:id model)
                                    :grant (:grant w)}
                                   {:principal clerk}))
        sid (mcp/open-session! eng)
        _ (mcp/bind-session! eng sid (cond-> {:seat (:id seat) :sitter clerk
                                              :sitting (:id sitting)}
                                       bench (assoc :bench bench)))]
    (assoc w :session (assoc (:session w) :mcp-session-id sid))))

(deftest a-bench-call-in-a-bound-sitting-carries-the-prepared-repo-and-branch
  (let [w (bound-world {:repo a-repo :branch a-branch})]
    (testing "find and read name neither, and reach the rig on the
              prepared worktree with no refusal"
      (doseq [[tool args] [["bench__find" {:mode "tree"}]
                           ["bench__read" {:path "src/a.clj"}]]]
        (let [r (power! w {:tool tool :arguments args})
              sent (last-arguments w)]
          (is (false? (:isError r)) (text-of r))
          (is (= tool (:tool (last (calls w)))))
          (is (= a-repo (:repo sent)) "the repository the sit prepared")
          (is (= a-branch (:branch sent)) "and its branch")))
      (is (= 2 (count (calls w)))))

    (testing "a call that names its own branch keeps it"
      (let [r (power! w {:tool "bench__read"
                         :arguments {:branch "bench/two" :path "src/a.clj"}})]
        (is (false? (:isError r)) (text-of r))
        (is (= "bench/two" (:branch (last-arguments w))))
        (is (= a-repo (:repo (last-arguments w))))))

    (testing "an explicit repository outside the filter is still refused"
      (let [before (count (calls w))
            r (power! w {:tool "bench__read"
                         :arguments {:repo another-repo :path "src/a.clj"}})]
        (is (true? (:isError r)) (text-of r))
        (is (str/includes? (text-of r) "bench.read"))
        (is (= before (count (calls w)))
            "nothing new reached the rig")))))

(deftest a-sitting-with-no-prepared-bench-gets-nothing-filled
  (let [w (bound-world nil)
        r (power! w {:tool "bench__read"
                     :arguments {:repo a-repo :path "src/a.clj"}})]
    (is (false? (:isError r)) (text-of r))
    (is (nil? (:branch (last-arguments w)))
        "today's behavior: the call carries what it named and no more")))

;; ── the token is a tool name too (bead waymark-fp62.6.3.12) ─────
;;
;; A SEAT READS TOKENS. The sit, the grant and the ask all name
;; `bench.read`, and the door used to answer only `bench__read` — so a
;; seat that called the door with the name it had been given got 404
;; on every power it held. A power that admits exactly ONE tool is now
;; a second spelling of that tool, and a power that admits two names
;; neither.

(deftest a-power-token-of-one-tool-is-that-tools-name-at-the-door
  (let [w (world [{:kind "bench.read" :actions [] :filter {:repo a-repo}}])]

    (testing "the seat spells the TOKEN, and the rig hears the tool"
      (let [r (power! w {:tool "bench.read"
                         :arguments {:repo a-repo :path "src/a.clj"}})]
        (is (false? (:isError r)) (text-of r))
        (is (= ["bench__read"] (mapv :tool (calls w)))
            "one call, under the tool the token resolved to")
        (is (= a-repo (:repo (last-arguments w))))))

    (testing "and the filter is judged on the call, as it is for the
              tool name: the token opens no wider door"
      (let [r (power! w {:tool "bench.read"
                         :arguments {:repo another-repo :path "src/a.clj"}})
            said (text-of r)]
        (is (true? (:isError r)) said)
        (is (str/includes? said "bench.read") "the token, to ask again for")
        (is (= 1 (count (calls w)))
            "nothing new reached the rig")))))

(deftest a-power-token-of-two-tools-names-neither-and-the-refusal-lists-both
  (let [w (world [{:kind "bench.look" :actions [] :filter {:repo a-repo}}])
        r (power! w {:tool "bench.look" :arguments {:repo a-repo}})
        said (text-of r)]
    (is (true? (:isError r)) said)
    (is (str/includes? said "bench__find") "the first tool it admits")
    (is (str/includes? said "bench__read")
        "and the second, so the seat calls one of them by name")
    (is (empty? (calls w)) "and nothing reached the rig")))

(deftest a-name-no-power-entry-names-is-refused-under-either-spelling
  (let [w (world [{:kind "bench.read" :actions [] :filter {:repo a-repo}}])]
    (doseq [nm ["bench__prepare" "bench.prepare" "bench.nothing"]]
      (let [r (power! w {:tool nm :arguments {:repo a-repo}})]
        (is (true? (:isError r))
            (str nm " must be refused: the engine's own tools are on no
                 token, and a token no row names is no name here"))))
    (is (empty? (calls w)) "and the rig was asked for nothing")))

;; ── acceptance 7: the discover answer publishes the constraints ─────

(defn- discover-doc
  "The waymark_discover answer, through the whole message layer."
  [eng session]
  (-> (mcp/message eng (mcp/door eng) (gate/rpc-of eng) session
                   {:jsonrpc "2.0" :id 1 :method "tools/call"
                    :params {:name "waymark_discover" :arguments {}}})
      (get-in [:result :content 0 :text])
      str
      wire/read-json))

(deftest the-discover-answer-says-which-powers-a-scope-may-narrow
  (let [w (world)
        eng (:eng w)
        ask (get-in (discover-doc eng {:principal colton}) [:doors :ask])
        constraints (:constraints ask)]
    (is (= ["path" "repo"] (:bench.read constraints))
        "an agent composing an ask reads the fields it may narrow by;
         the list is sorted, because it is a union over every row that
         names the token and no one row's order can speak for it")
    (is (= ["path" "repo"] (:bench.find constraints)))
    (is (= ["path" "repo"] (:bench.edit constraints)))
    (is (= ["repo"] (:bench.pull constraints))
        "pull moves a whole checkout, so no path could narrow it")
    (is (= ["repo"] (:bench.feedback constraints))
        "and a feedback reads a whole branch, so no path could narrow
         that one either")
    (is (not-any? #(str/starts-with? (name %) "telegram")
                  (keys constraints))
        "Gate's powers name no constraints, so they are ABSENT here —
         a token missing from this map is a token to ask for whole")
    (is (some? (:powers_note ask))
        "the vocabulary the constraints narrow still rides beside them")

    (testing "waymark_powers carries the same words on each tool"
      (let [worn (wear! eng [{:kind "bench.read" :actions []}])
            doc (-> (mcp/message eng (mcp/door eng) (gate/rpc-of eng)
                                 {:principal clerk
                                  :visibility (:visibility worn)}
                                 {:jsonrpc "2.0" :id 1 :method "tools/call"
                                  :params {:name "waymark_powers"
                                           :arguments {}}})
                    (get-in [:result :content 0 :text])
                    str
                    wire/read-json)]
        (is (= ["repo" "path"]
               (get-in doc [:links :bench__read :constraints]))
            "the tool says what a narrower grant on it could name, in
             the row's own words rather than the sorted union")))))

;; ── a glob on a constrained field the entry names (ticket b9c1b8e4) ─

(def ^:private house-powers
  "A household server's policy: automation_put says its `entity_id`
  takes globs, and service_call names the same field and says nothing,
  so its filter values stay exact words."
  [{:power "homeassistant.automation_put" :tools ["automation_put"]
    :why false :constraints ["entity_id"] :glob_constraints ["entity_id"]}
   {:power "homeassistant.service_call" :tools ["service_call"]
    :why false :constraints ["entity_id"]}])

(defn- house-world
  "One engine with a `homeassistant` row over a fake server that logs
  every call, and the clerk wearing a grant over `scope`."
  [scope]
  (let [log (atom [])
        house (fn [method params]
                (case method
                  "tools/list"
                  {:tools (mapv (fn [nm]
                                  {:name nm
                                   :description (str "The house's " nm ".")
                                   :inputSchema
                                   {:type "object"
                                    :properties {:entity_id {:type "string"}}}})
                                ["automation_put" "service_call"])}
                  "tools/call"
                  (do (swap! log conj {:tool (str "homeassistant__"
                                                  (:name params))
                                       :arguments (:arguments params)})
                      {:isError false
                       :content [{:type "text"
                                  :text (wire/write-json
                                         {:ok (str (:name params))})}]})
                  (throw (ex-info (str "the fake house speaks no " method)
                                  {}))))
        eng (doto (engine/engine
                   {:storage (memory/storage)
                    :resources [caps/capability ticket-kind]
                    :now-fn (fn [] clock)
                    :services {:mcp-servers
                               {:client-fn
                                (fn [row]
                                  (when (= "homeassistant"
                                           (str (get-in row [:data :name])))
                                    house))
                                :gate-rpc (fake-gate)}}})
              (gate/ensure-gate-row!))
        _ (inv/create! eng :mcp_server
                       {:name "homeassistant"
                        :transport "stdio"
                        :command "python3"
                        :args ["-m" "house" "--stdio"]
                        :powers house-powers
                        :note "The house, beside the engine."}
                       {:principal colton})
        worn (wear! eng scope)]
    {:eng eng
     :log log
     :grant (:id worn)
     :session {:principal clerk :visibility (:visibility worn)}}))

(deftest a-filter-globs-only-on-a-field-the-entry-says-takes-globs
  (let [w (house-world
           [{:kind "homeassistant.automation_put" :actions []
             :filter {:entity_id "automation.school_*"}}
            {:kind "homeassistant.service_call" :actions []
             :filter {:entity_id "media_player.*,media_player.den"}}])]

    (testing "the glob admits an entity it matches, and the server
              hears the call"
      (let [r (power! w {:tool "homeassistant__automation_put"
                         :arguments {:entity_id
                                     "automation.school_departure"}})]
        (is (false? (:isError r)) (text-of r))
        (is (= 1 (count (calls w))))
        (is (= "automation.school_departure"
               (:entity_id (last-arguments w))))))

    (testing "…and refuses one it does not, in-process"
      (let [r (power! w {:tool "homeassistant__automation_put"
                         :arguments {:entity_id "automation.porch_lights"}})
            said (text-of r)]
        (is (true? (:isError r)) said)
        (is (str/includes? said "entity_id") "the field that failed")
        (is (str/includes? said "automation.porch_lights")
            "what this call carried")
        (is (= 1 (count (calls w))) "nothing reached the server")))

    (testing "an entry that names no glob field still matches exact
              words: `media_player.*` is a word there, not a glob"
      (let [r (power! w {:tool "homeassistant__service_call"
                         :arguments {:entity_id "media_player.kitchen"}})]
        (is (true? (:isError r)) (text-of r))
        (is (= 1 (count (calls w)))))
      (let [r (power! w {:tool "homeassistant__service_call"
                         :arguments {:entity_id "media_player.den"}})]
        (is (false? (:isError r)) (text-of r))
        (is (= 2 (count (calls w))))))

    (testing "discover says which fields take a glob, beside the
              constraints, and leaves out the token that names none"
      (let [ask (get-in (discover-doc (:eng w) {:principal colton})
                        [:doors :ask])]
        (is (= {:homeassistant.automation_put ["entity_id"]}
               (:glob_constraints ask)))
        (is (= ["entity_id"]
               (:homeassistant.service_call (:constraints ask)))
            "the constraints map keeps its shape")))))

;; ── the protected paths: the engine decides allow_protected ─────────

(deftest a-caller-never-sets-allow-protected-the-scope-does
  (testing "a caller-sent allow_protected is dropped, so the rig refuses
            the .github/ write as it always has"
    (let [w (world [{:kind "bench.edit" :actions [] :filter {:repo a-repo}}])
          r (power! w {:tool "bench__edit"
                       :arguments {:repo a-repo
                                   :path ".github/workflows/test.yml"
                                   :allow_protected true}})]
      (is (false? (:isError r)) (text-of r))
      (is (not (contains? (last-arguments w) :allow_protected))
          "the rig never hears the caller's flag")))

  (testing "a bench.edit filter that names the path sets the flag for
            that path and not for its neighbour"
    (let [w (world [{:kind "bench.edit" :actions []
                     :filter {:repo a-repo
                              :path ".github/workflows/test.yml,src/**"}}])]
      (power! w {:tool "bench__edit"
                 :arguments {:repo a-repo :path ".github/workflows/test.yml"}})
      (is (true? (:allow_protected (last-arguments w))))
      (let [before (count (calls w))
            r (power! w {:tool "bench__edit"
                         :arguments {:repo a-repo :path "src/a.clj"
                                     :move_to ".github/workflows/other.yml"}})]
        (is (true? (:isError r)) (text-of r))
        (is (= before (count (calls w)))
            "a move into a protected path the filter does not name"))))

  (testing "a neighbour the filter does not admit refuses before the rig"
    (let [w (world [{:kind "bench.edit" :actions []
                     :filter {:repo a-repo :path ".github/workflows/test.yml"}}])
          r (power! w {:tool "bench__edit"
                       :arguments {:repo a-repo
                                   :path ".github/workflows/other.yml"
                                   :allow_protected true}})]
      (is (true? (:isError r)) (text-of r))
      (is (empty? (calls w)))))

  (testing "a `*` path glob admits the write but never sets the flag"
    (let [w (world [{:kind "bench.edit" :actions []
                     :filter {:repo a-repo :path "*"}}])]
      (power! w {:tool "bench__edit"
                 :arguments {:repo a-repo :path ".github/workflows/test.yml"
                             :allow_protected true}})
      (is (= 1 (count (calls w))))
      (is (not (contains? (last-arguments w) :allow_protected)))))

  (testing "a path that climbs out through `..` never sets the flag"
    (let [w (world [{:kind "bench.edit" :actions []
                     :filter {:repo a-repo :path ".github/workflows/*"}}])]
      (power! w {:tool "bench__edit"
                 :arguments {:repo a-repo
                             :path ".github/workflows/../../.claude/hooks/x.sh"}})
      (is (= 1 (count (calls w))))
      (is (not (contains? (last-arguments w) :allow_protected)))))

  (testing "a write outside the protected prefixes is unchanged"
    (let [w (world [{:kind "bench.edit" :actions []
                     :filter {:repo a-repo :path ".github/**,docs/**"}}])]
      (power! w {:tool "bench__edit"
                 :arguments {:repo a-repo :path "docs/a.md"}})
      (is (= {:repo a-repo :path "docs/a.md"}
             (select-keys (last-arguments w) [:repo :path :allow_protected]))))))

;; ── a batch is judged item by item (ticket 0e6b3ffc) ────────────────

(deftest a-batch-is-judged-item-by-item-the-way-one-edit-is
  (testing "every protected item named by the seat's bench.edit filter
            sets the flag for the batch"
    (let [w (world [{:kind "bench.edit" :actions []
                     :filter {:repo a-repo
                              :path ".claude/hooks/x.sh,src/**"}}
                    {:kind "bench.edit_many" :actions []
                     :filter {:repo a-repo}}])]
      (power! w {:tool "bench__edit_many"
                 :arguments {:repo a-repo
                             :edits [{:path "src/a.clj" :old "a" :new "b"}
                                     {:path ".claude/hooks/x.sh"
                                      :old "a" :new "b"}]}})
      (is (= 1 (count (calls w))))
      (is (true? (:allow_protected (last-arguments w)))
          "the seat's scope named the path, so the engine sets the flag")))

  (testing "an item no protected glob names refuses the whole call,
            naming the item and the path"
    (let [w (world [{:kind "bench.edit" :actions []
                     :filter {:repo a-repo :path ".claude/hooks/x.sh"}}
                    {:kind "bench.edit_many" :actions []
                     :filter {:repo a-repo}}])
          r (power! w {:tool "bench__edit_many"
                       :arguments {:repo a-repo
                                   :edits [{:path ".claude/hooks/x.sh"}
                                           {:path ".github/workflows/t.yml"}]}})
          said (text-of r)]
      (is (true? (:isError r)) said)
      (is (str/includes? said "bench__edit_many item 2") said)
      (is (str/includes? said ".github/workflows/t.yml") said)
      (is (empty? (calls w)) "nothing reached the rig")))

  (testing "a batch that writes nothing protected is untouched"
    (let [w (world [{:kind "bench.edit_many" :actions []
                     :filter {:repo a-repo :path "src/**"}}])]
      (power! w {:tool "bench__edit_many"
                 :arguments {:repo a-repo :edits [{:path "src/a.clj"}]}})
      (is (= 1 (count (calls w))))
      (is (not (contains? (last-arguments w) :allow_protected))))))

;; ── the batch is a write power where the engine asks which power
;;    writes (ticket d4b13a7f) ──────────────────────────────────────

(deftest a-scope-that-names-edit-many-and-not-edit-still-writes-its-repository
  (testing "the batch chooses the seat's repository, as the single edit does"
    (let [repositories @#'mcp/seat-repositories
          reads (fn [repos] {:kind "bench.read" :actions [] :filter {:repo repos}})]
      (is (= {:repo a-repo :reference [another-repo]}
             (repositories
              {:data {:scope [{:kind "bench.edit_many" :actions []
                               :filter {:repo a-repo}}
                              (reads (str a-repo "," another-repo))]}}))
          "the reading entry names two, and the writing one picks between them")
      (is (nil? (repositories
                 {:data {:scope [{:kind "bench.edit_many" :actions []}
                                 (reads a-repo)]}}))
          "a batch entry with no repo chooses none: it is asked to carry one")
      (is (true? (@#'mcp/code-seat?
                  {:data {:scope [{:kind "bench.edit_many" :actions []
                                   :filter {:repo a-repo}}]}}))
          "and a seat that holds only the batch builds")))

  (testing "bench__prepare reads the batch as a write power on the repository"
    (let [admits? @#'gate/bench-edit-admits-repo?
          vis-of (fn [scope] (get-in (world scope) [:session :visibility]))
          many (vis-of [{:kind "bench.edit_many" :actions []
                         :filter {:repo a-repo}}])]
      (is (true? (admits? many a-repo)))
      (is (false? (admits? many another-repo))
          "the filter still narrows it to its own repository")
      (is (false? (admits? (vis-of [{:kind "bench.read" :actions []
                                     :filter {:repo a-repo}}])
                           a-repo))
          "a seat that only reads holds no write power")
      (is (true? (admits? (vis-of [{:kind "bench.edit" :actions []
                                    :filter {:repo a-repo}}])
                          a-repo))
          "and the single edit stands as it did"))))

;; ── a path-narrowed entry adds a file; it does not shut the bench
;;    (ticket d30c390f) ─────────────────────────────────────────────

(deftest a-path-narrowed-entry-does-not-shut-the-bench
  (let [home @#'mcp/walk-home
        both (str a-repo "," another-repo)
        entry (fn [kind repos] {:kind kind :actions [] :filter {:repo repos}})
        narrowed {:kind "bench.edit" :actions []
                  :filter {:repo another-repo :path ".github/workflows/tests.yml"}}
        seat (fn [scope] {:data {:scope scope}})
        row-in (fn [repo] {"data" {"repo" repo}})]
    (testing "general entries for two repositories and one path entry for the second"
      (let [s (seat [(entry "bench.read" both) (entry "bench.edit" both) narrowed])]
        (is (= [a-repo nil] (home s (row-in a-repo)))
            "the row in the first repository gets its worktree")
        (is (= [another-repo nil] (home s (row-in another-repo))))
        (is (true? (@#'mcp/several-repositories? s))
            "and the path entry does not make the choosers disagree")))
    (testing "a power that names only the other repository still shuts it"
      (let [[repo note] (home (seat [(entry "bench.read" another-repo)
                                     (entry "bench.edit" both)
                                     narrowed])
                              (row-in a-repo))]
        (is (nil? repo))
        (is (str/includes? (str note) "do not all reach") note)))
    (testing "a power held only by path entries still chooses by them"
      (is (= a-repo
             (:repo (@#'mcp/seat-repositories
                     (seat [{:kind "bench.edit" :actions []
                             :filter {:repo a-repo :path "src/**"}}
                            (entry "bench.read" a-repo)]))))))
    (testing "a path entry for another repository leaves the one-repository seat its repository"
      (is (= a-repo
             (:repo (@#'mcp/seat-repositories
                     (seat [(entry "bench.edit" a-repo)
                            narrowed
                            (entry "bench.read" both)]))))))))

;; ── the sitting that holds the row writes its branch (ticket d7c854b3) ─

(deftest a-bench-write-from-a-sitting-that-no-longer-holds-the-ticket-is-refused
  (let [w (world [{:kind "bench.read" :actions [] :filter {:repo a-repo}}
                  {:kind "bench.edit" :actions [] :filter {:repo a-repo}}])
        eng (:eng w)
        seat (a-seat! eng)
        model (a-model! eng)
        ticket "t-held-one"
        ;; no repo_policy kind in this engine: the default pattern
        branch (str "waymark/" ticket)
        claim! (requiring-resolve 'waymark10.server.seats/claim-rows!)
        sit! (fn []
               (let [s (:row (inv/create! eng :sitting
                                          {:seat (:id seat) :model (:id model)
                                           :grant (:grant w)}
                                          {:principal clerk}))
                     sid (mcp/open-session! eng)]
                 (mcp/bind-session! eng sid {:seat (:id seat) :sitter clerk
                                             :sitting (:id s)
                                             :bench {:repo a-repo :branch branch}})
                 (claim! eng (:id s) [ticket])
                 {:id (str (:id s))
                  :w (assoc w :session (assoc (:session w) :mcp-session-id sid))}))
        edit! (fn [bound]
                (power! bound {:tool "bench__edit"
                               :arguments {:path "src/a.clj"}}))
        a (sit!)]

    (testing "the sitting that holds the ticket writes its branch"
      (let [r (edit! (:w a))]
        (is (false? (:isError r)) (text-of r))
        (is (= branch (:branch (last-arguments w))))))

    (inv/invoke! eng :sitting (:id a) :close
                 {:input_tokens 1000 :output_tokens 100
                  :cache_read_tokens 0 :cache_write_tokens 0 :turns 1}
                 {:principal clerk})

    (testing "closed, it is refused before the rig"
      (let [n (count (calls w))
            r (edit! (:w a))
            said (text-of r)]
        (is (true? (:isError r)) said)
        (is (str/includes? said (str "no longer holds ticket " ticket)) said)
        (is (str/includes? said "stop, do not write") said)
        (is (= n (count (calls w))) "nothing reached the rig")))

    (let [b (sit!)]
      (testing "once a second sitting is handed the ticket, the first is
                refused naming it, and the second is accepted"
        (let [n (count (calls w))
              r (edit! (:w a))
              said (text-of r)]
          (is (true? (:isError r)) said)
          (is (str/includes? said (str "held by sitting " (:id b))) said)
          (is (= n (count (calls w))) "nothing reached the rig"))
        (let [r (edit! (:w b))]
          (is (false? (:isError r)) (text-of r))
          (is (= branch (:branch (last-arguments w))))))

      (testing "a read from the closed sitting still forwards"
        (let [r (power! (:w a) {:tool "bench__read"
                                :arguments {:path "src/a.clj"}})]
          (is (false? (:isError r)) (text-of r)))))))

(deftest a-descriptive-branch-belongs-to-the-ticket-whose-branch-field-names-it
  ;; Ticket 81931c1c: the default pattern waymark/* would read
  ;; `groom-reopens` as the row id; the ticket's own `branch` field says
  ;; which row the branch is.
  (let [w (world [{:kind "bench.read" :actions [] :filter {:repo a-repo}}
                  {:kind "bench.edit" :actions [] :filter {:repo a-repo}}])
        eng (:eng w)
        model (a-model! eng)
        seat (:row (inv/create! eng :seat
                                {:name "narrow-power-tickets"
                                 :charter "Build what each open ticket asks for."
                                 :scope [{:kind "capability" :actions []}
                                         {:kind "ticket" :actions ["close"]
                                          :filter {:state "open"}}]
                                 :walk "ticket"
                                 :standing_ttl_seconds 604800
                                 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 1000000}
                                {:principal colton}))
        branch "waymark/groom-reopens"
        mine (:row (inv/create! eng :ticket {:title "Groom reopens" :branch branch}
                                {:principal colton}))
        theirs (:row (inv/create! eng :ticket {:title "Another" :branch "waymark/another"}
                                  {:principal colton}))
        claim! (requiring-resolve 'waymark10.server.seats/claim-rows!)
        sit! (fn [ticket]
               (let [s (:row (inv/create! eng :sitting
                                          {:seat (:id seat) :model (:id model)
                                           :grant (:grant w)}
                                          {:principal clerk}))
                     sid (mcp/open-session! eng)]
                 (mcp/bind-session! eng sid {:seat (:id seat) :sitter clerk
                                             :sitting (:id s)
                                             :bench {:repo a-repo :branch branch}})
                 (claim! eng (:id s) [(str (:id ticket))])
                 {:id (str (:id s))
                  :w (assoc w :session (assoc (:session w) :mcp-session-id sid))}))
        edit! (fn [bound]
                (power! bound {:tool "bench__edit"
                               :arguments {:path "src/a.clj"}}))
        a (sit! mine)
        b (sit! theirs)]

    (testing "the ticket's own sitting writes its descriptive branch"
      (let [r (edit! (:w a))]
        (is (false? (:isError r)) (text-of r))
        (is (= branch (:branch (last-arguments w))))))

    (testing "a sitting handed another ticket is refused, naming the ticket"
      (let [n (count (calls w))
            r (edit! (:w b))
            said (text-of r)]
        (is (true? (:isError r)) said)
        (is (str/includes? said (str "no longer holds ticket " (:id mine))) said)
        (is (not (str/includes? said "groom-reopens")) said)
        (is (= n (count (calls w))) "nothing reached the rig")))))
