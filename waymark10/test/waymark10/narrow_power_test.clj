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
        ["prepare" "find" "read" "edit" "pull" "feedback"]))

(def ^:private bench-powers
  "The bench row's policy, with the CONSTRAINTS this bead adds: find,
  read and edit may be narrowed by the repository and by the path;
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

(defn- fresh-engine
  "An engine over the two fakes: the rig is the `bench` row's client
  through the `:client-fn` seam, and Gate is the bridge row's through
  `:gate-rpc`. The capability registry rides along as the app declares
  it, because a dotted scope entry no row names is judged against it."
  [log]
  (let [rig (fake-rig log)]
    (doto (engine/engine
           {:storage (memory/storage)
            :resources [caps/capability]
            :now-fn (fn [] clock)
            :services {:mcp-servers
                       {:client-fn (fn [row]
                                     (when (= "bench"
                                              (str (get-in row [:data :name])))
                                       rig))
                        :gate-rpc (fake-gate)}}})
      (gate/ensure-gate-row!))))

(defn- a-bench-row!
  "The bench, as a row: stdio beside the engine, the five powers with
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
