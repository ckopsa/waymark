(ns waymark10.one-vocabulary-test
  "One vocabulary for dotted tokens (bead waymark-fp62.10.4,
  docs/spec-mcp-servers.md § 4): after the `powers` list became the
  policy, the same word was registered twice — once on the
  mcp_server row that enforces it, once as a capability row nobody
  reads any more. This namespace holds the five acceptance cases of
  the rule that ended that.

  THE RULE. `scope-names-real-kinds` reads the rows' power tokens
  FIRST: a dotted token any non-retired server names is real because
  the row that enforces it says so. The capability registry answers
  only for a token no server names — this engine's own two powers,
  `feed.preview_as` and `schedule.write`, which have no server row to
  live in. A boot sweep retires the rest.

  Memory storage, a held clock and an in-process fake server, the
  shape mcp-servers-test already uses: no network, no database, real
  rows through the engine."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private clock (Instant/parse "2026-09-18T09:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private clerk
  (t/principal {:id "mail-clerk" :type :agent :display "Clerk"
                :model "one-vocabulary-model"}))

(def ^:private emila-tools
  [{:name "read" :description "Read one message."
    :inputSchema {:type "object" :properties {:uid {:type "string"}}}}
   {:name "send" :description "Send a message."
    :inputSchema {:type "object" :properties {:to {:type "string"}}}}])

(def ^:private emila-powers
  "The mail server's policy. `email.read` names one constraint
  (waymark-fp62.6.3.5), so a grant may narrow it by `folder` and the
  filtered-grant cases below still stand; `email.send` names none, so
  no grant may filter it at all."
  [{:power "email.read" :tools ["read"] :why false :constraints ["folder"]}
   {:power "email.send" :tools ["send"] :why true}])

(defn- fake-server
  "An in-process MCP server: it answers tools/list and nothing else is
  asked of it here."
  []
  (fn [method _params]
    (case method
      "tools/list" {:tools emila-tools}
      {})))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [caps/capability]
                  :now-fn (fn [] clock)
                  :services {:mcp-servers
                             {:client-fn (fn [_row] (fake-server))}}}))

(defn- a-server!
  "One mcp_server row, created by a person through the engine."
  [eng body]
  (:row (inv/create! eng :mcp_server
                     (merge {:transport "http" :url "http://fake.invalid/mcp/"}
                            body)
                     {:principal colton})))

(defn- a-capability!
  "One registry row, created by a person through the engine."
  [eng body]
  (:row (inv/create! eng :capability body {:principal colton})))

(defn- gate-row
  "A capability row as the old boot seed wrote one: Gate stands in
  front of the data, and the address rides the sentence."
  [token]
  {:token token
   :description (str token " through Gate.")
   :enforced_by "gate-mcp (192.168.1.40:8100)"})

(defn- a-grant!
  "A grant naming `scope`, minted by a person → the row."
  [eng scope]
  (:row (inv/create! eng :grant
                     {:audience (:id clerk) :scope scope}
                     {:principal colton})))

(defn- refusal
  "The problem ex-data of a write that was refused, or nil when it was
  served — {:guard … :detail … :status …}."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(defn- state-of [eng kind id]
  (:state (store/with-tx (:storage eng)
            (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))))

;; ── acceptance 1: the row is the word ───────────────────────────────

(deftest a-token-only-a-servers-powers-name-is-a-real-kind
  (let [eng (fresh-engine)
        row (a-server! eng {:name "emila" :powers emila-powers})]
    (is (= :live (:state row)) "the fake server answered, so the row is live")
    (is (empty? (store/with-tx (:storage eng)
                  (fn [tx] (store/query-rows (:storage eng) tx :capability
                                             {:token "email.read"} {:limit 1}))))
        "and NO capability row carries the token — the row is the only speller")

    (testing "a scope naming it is accepted"
      (let [grant (a-grant! eng [{:kind "email.read" :actions []}])]
        (is (= ["email.read"] (mapv :kind (get-in grant [:data :scope])))
            "the entry landed as written")))

    (testing "and a token NO row and no registry names is still refused"
      (let [p (refusal #(a-grant! eng [{:kind "unicorn.ride" :actions []}]))]
        (is (= :scope-names-real-kinds (:guard p)))
        (is (re-find #"unicorn\.ride" (str (:detail p)))
            "the refusal spells the token that failed")))

    (testing "the server retired, the same token is an unknown kind"
      (inv/invoke! eng :mcp_server (str (:id row)) :retire {}
                   {:principal colton})
      (is (= :retired (state-of eng :mcp_server (:id row))))
      (let [p (refusal #(a-grant! eng [{:kind "email.read" :actions []}]))]
        (is (= :scope-names-real-kinds (:guard p))
            "a retired row speaks for nothing")
        (is (re-find #"email\.read" (str (:detail p))))))))

;; ── acceptance 2: the two the registry still answers for ────────────

(deftest feed-preview-as-is-nameable-with-no-server-naming-it
  (let [eng (fresh-engine)
        _ (a-capability! eng caps/feed-preview-as)
        _ (a-capability! eng schedules/write-capability)]
    (is (empty? (servers/power-tokens eng))
        "no mcp_server row exists at all, so the powers half is empty")
    (let [entry (first (get-in (a-grant! eng [{:kind caps/feed-preview-as-token
                                               :actions []
                                               :filter {:member "jack"}}])
                               [:data :scope]))]
      (is (= "feed.preview_as" (:kind entry))
          "this engine's own power is named from the registry, as before")
      (is (= {:member "jack"} (:filter entry))
          "and the constraint rides it, untouched"))
    (is (= ["feed.preview_as" "schedule.write"] (servers/registered-tokens eng))
        "and both engine powers stand in the registry")))

;; ── acceptance 3: the boot sweep ────────────────────────────────────

(deftest the-boot-sweep-retires-the-rows-a-server-took-over
  (let [eng (fresh-engine)
        _ (a-server! eng {:name "emila" :powers emila-powers})
        read-row (a-capability! eng (gate-row "email.read"))
        send-row (a-capability! eng (gate-row "email.send"))
        costco (a-capability! eng (gate-row "costco.read"))
        feed (a-capability! eng caps/feed-preview-as)
        sched (a-capability! eng schedules/write-capability)
        swept (servers/sweep-capabilities! eng)]
    (is (= ["email.read" "email.send"] swept)
        "the two tokens the server's powers name, and only those")
    (is (= :retired (state-of eng :capability (:id read-row))))
    (is (= :retired (state-of eng :capability (:id send-row))))
    (is (= :active (state-of eng :capability (:id costco)))
        "a Gate token no live server names is nobody's duplicate yet")
    (is (= :active (state-of eng :capability (:id feed)))
        "and this engine's own two powers stand: no server enforces them")
    (is (= :active (state-of eng :capability (:id sched))))

    (testing "the retirement is a transition with the engine on it, not
              a store write nobody witnessed"
      (let [ts (store/with-tx (:storage eng)
                 (fn [tx] (store/transitions
                           (:storage eng) tx
                           {:kind :capability :resource-id (str (:id read-row))}
                           {:newest-first true :limit 1})))]
        (is (= :retire (:action (first ts))))
        (is (= "waymark10-mcp-servers" (get-in (first ts) [:actor :id])))))

    (testing "and the pass is idempotent"
      (is (= [] (servers/sweep-capabilities! eng))
          "a second pass finds them retired and writes nothing"))

    (testing "a scope still names what the sweep retired, because the
              ROW says the word now"
      (is (= ["email.read"]
             (mapv :kind (get-in (a-grant! eng [{:kind "email.read"
                                                 :actions []}])
                                 [:data :scope])))))))

;; ── acceptance 4: one list at doors.ask.powers ──────────────────────

(defn- discover-doc
  "The waymark_discover answer, through the whole message layer."
  [eng session]
  (-> (mcp/message eng (mcp/door eng) (gate/rpc-of eng) session
                   {:jsonrpc "2.0" :id 1 :method "tools/call"
                    :params {:name "waymark_discover" :arguments {}}})
      (get-in [:result :content 0 :text])
      str
      wire/read-json))

(deftest doors-ask-powers-lists-the-servers-tokens-and-the-engines-own
  (let [eng (fresh-engine)
        _ (a-server! eng {:name "emila" :powers emila-powers})
        _ (a-capability! eng caps/feed-preview-as)
        ;; an unscoped caller: the powers list is VOCABULARY, not a
        ;; leash's shadow, so it reads the same for everybody
        powers (get-in (discover-doc eng {:principal colton})
                       [:doors :ask :powers])]
    (is (= ["email.read" "email.send" "feed.preview_as"] powers)
        "the servers' tokens and the standing registry rows, as one list")
    (is (= powers (servers/nameable-tokens eng))
        "the door says exactly what the guard judges against")))

;; ── acceptance 5: the grant-check door did not move ─────────────────

(deftest grant-check-answers-as-before-for-a-granted-token
  (let [eng (fresh-engine)
        _ (a-server! eng {:name "emila" :powers emila-powers})
        grant (a-grant! eng [{:kind "email.read" :actions []
                              :filter {:folder "inbox"}}])
        gid (str (:id grant))]
    (is (false? (:allowed (grants/check-capability eng gid (:id clerk)
                                                   "email.read")))
        "an offered grant confers nothing yet")
    (inv/invoke! eng :grant gid :accept {} {:principal clerk})
    (let [ans (grants/check-capability eng gid (:id clerk) "email.read")]
      (is (true? (:allowed ans)))
      (is (= {:folder "inbox"} (:constraints ans))
          "the filter rides the yes, the enforcement point's to interpret"))
    (is (false? (:allowed (grants/check-capability eng gid (:id clerk)
                                                   "email.send")))
        "a token the grant never named is the same false as a dead grant")))
