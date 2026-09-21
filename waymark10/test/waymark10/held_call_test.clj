(ns waymark10.held-call-test
  "The held call (docs/spec-mcp-servers.md R-14, bead
  waymark-fp62.10.2): acceptance 18 to 25, one deftest each, against
  an in-process fake server registered through the engine's
  `:client-fn` seam. No network, memory storage, real rows through
  the engine.

  THE RIG'S ONE PROMISE, and the one the bead asks for by name: the
  fake server NEVER receives the held call before a person allows it.
  Every test that holds a call asserts on the fake's own log, which
  is the only honest witness that the wire was not touched.

  The wire-boundary effect is walked by hand here (`allow!` below).
  The router walks it for real. `held/after-allow!` sits beside
  `grants/approval-effects!` in the one invoke path both the HTTP
  door and the MCP door take. Calling it by hand here keeps this
  suite free of a transport it is not about."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp-client :as client]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.wakes :as wakes]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the fake server ─────────────────────────────────────────────────

(def ^:private three-tools
  [{:name "read" :description "Read one message."
    :inputSchema {:type "object" :properties {:uid {:type "string"}}}}
   {:name "send" :description "Send a message."
    :inputSchema {:type "object"
                  :properties {:to {:type "string"}
                               :text {:type "string"}}}}
   {:name "wire" :description "Move money."
    :inputSchema {:type "object" :properties {:amount {:type "string"}}}}])

(defn- fake-server
  "An in-process MCP server: (fn [method params]) → the JSON-RPC
  :result. Every call lands on `log`, which is what proves the wire
  was not touched. `down?` is an atom a test raises to break the
  wire under a call that a person has already allowed."
  ([log] (fake-server log (atom false)))
  ([log down?]
   (fn [method params]
     (swap! log conj {:method method :params params})
     (when (and @down? (= "tools/call" method))
       (throw (client/unreachable "the fake is down.")))
     (case method
       "tools/list" {:tools three-tools}
       "tools/call" {:content [{:type "text"
                                :text (str "answered " (:name params) " "
                                           (wire/write-json
                                            (:arguments params)))}]
                     :isError false}))))

(def ^:private emila-powers
  "The policy under test. `email.read` runs at once. `email.send`
  waits on a person and marks the two fields the person reads.
  `email.wire` waits on a person and marks none, so the line falls
  back to the why."
  [{:power "email.read" :tools ["read"] :approval "none"}
   {:power "email.send" :tools ["send"] :approval "person"
    :shown ["to" "text"]}
   {:power "email.wire" :tools ["wire"] :approval "person"}])

(def ^:private clock-start (Instant/parse "2026-09-21T09:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private approver
  (t/principal {:id "mom" :display "Mom" :roles #{"approver"}}))

(def ^:private a-sibling (t/principal {:id "iris" :display "Iris"}))

(def ^:private clerk
  (t/principal {:id "mail-clerk" :type :agent :display "Clerk"
                :model "held-call-test-model"}))

(def ^:private other-clerk
  (t/principal {:id "other-clerk" :type :agent :display "Other"}))

;; ── the world ───────────────────────────────────────────────────────

(defn- fresh-engine
  "An engine whose clock a test can move and whose one server is the
  fake."
  [clock client]
  (engine/engine {:storage (memory/storage)
                  :resources [caps/capability]
                  :now-fn (fn [] @clock)
                  :services {:mcp-servers
                             {:client-fn (fn [row]
                                           (when (= "emila"
                                                    (get-in row [:data :name]))
                                             client))}}}))

(defn- mint-capabilities! [eng]
  (doseq [token ["email.read" "email.send" "email.wire"]]
    (inv/create! eng :capability
                 {:token token
                  :description (str token " through a server row.")
                  :enforced_by "this engine's own power door"}
                 {:principal colton})))

(defn- wear!
  "A grant naming `scope`, minted by a person and accepted by
  `who` → the session the MCP door reads."
  [eng who scope]
  (let [row (:row (inv/create! eng :grant
                               {:audience (:id who) :scope scope}
                               {:principal colton}))
        gid (str (:id row))]
    (inv/invoke! eng :grant gid :accept {} {:principal who})
    {:grant-id gid
     :session {:principal who
               :visibility (grants/visibility eng gid who)}}))

(def ^:private mail-scope
  [{:kind "email.read" :actions []}
   {:kind "email.send" :actions []}
   {:kind "email.wire" :actions []}])

(defn- world
  "One engine, one live server row, the registry, and the clerk
  wearing all three mail powers. → {:eng :log :clock :session
  :grant-id :down?}."
  []
  (let [log (atom [])
        down? (atom false)
        clock (atom clock-start)
        eng (fresh-engine clock (fake-server log down?))
        _ (mint-capabilities! eng)
        _ (inv/create! eng :mcp_server
                       {:name "emila" :transport "http"
                        :url "http://fake.invalid/mcp/"
                        :powers emila-powers}
                       {:principal colton})
        {:keys [session grant-id]} (wear! eng clerk mail-scope)]
    {:eng eng :log log :clock clock :down? down?
     :session session :grant-id grant-id}))

(defn- calls
  "What the fake was asked to CALL, past the create's one tools/list."
  [{:keys [log]}]
  (filterv #(= "tools/call" (:method %)) @log))

(defn- tool!
  "One tools/call through the whole message layer → the result."
  [{:keys [eng session]} tool-name args]
  (get-in (mcp/message eng (mcp/door eng) (gate/rpc-of eng) session
                       {:jsonrpc "2.0" :id 1 :method "tools/call"
                        :params {:name tool-name :arguments args}})
          [:result]))

(defn- text-of [result] (str (get-in result [:content 0 :text])))
(defn- doc-of [result] (wire/read-json (text-of result)))

(defn- held-row
  "One held_call row, decoded."
  [{:keys [eng]} id]
  (let [rdef (get (inv/resources eng) :held_call)]
    (inv/decode-row rdef
                    (store/with-tx (:storage eng)
                      (fn [tx] (store/load-row (:storage eng) tx :held_call
                                               (str id) {}))))))

(defn- send!
  "The held send, made by the clerk through the power door → the id of
  the row it minted."
  ([w] (send! w {:to "otto@example.test" :text "On my way."}))
  ([w args]
   (let [out (tool! w "waymark_power"
                    {:tool "emila__send"
                     :arguments (assoc args :why "The household asked.")})]
     {:result out :id (str (:held_call (doc-of out)))})))

(defn- allow!
  "A person's tap, and the wire-boundary effect the router walks
  after it. → the row's state."
  [{:keys [eng] :as w} id who]
  (let [rdef (get (inv/resources eng) :held_call)
        out (inv/invoke! eng :held_call (str id) :allow {} {:principal who})]
    (held/after-allow! eng rdef :allow out)
    (:state (held-row w id))))

(defn- refused
  "The problem a thunk throws, or nil when it answered."
  [thunk]
  (try (thunk) nil (catch Exception e e)))

;; ── 18 · a person tool holds the call, and the rig hears nothing ────

(deftest a-person-tool-answers-held-and-mints-a-row
  (let [w (world)
        {:keys [result id]} (send! w)
        doc (doc-of result)]

    (testing "the caller is answered at once, and it is not a refusal"
      (is (false? (:isError result))
          "a held call is an answer; a caller that read it as a refusal
           would learn to stop asking")
      (is (true? (:held doc)))
      (is (= "waiting on a person's tap" (:note doc)))
      (is (not (str/blank? id))))

    (testing "and the fake server heard nothing at all"
      (is (= [] (calls w))
          "the rig must never receive a held call before the allow"))

    (testing "the row is the notice and the record"
      (let [row (held-row w id)]
        (is (= :held (:state row)))
        (is (= "emila__send" (get-in row [:data :tool])))
        (is (= "mail-clerk" (get-in row [:data :caller])))
        (is (= "The household asked." (get-in row [:data :why])))
        (is (= {:to "otto@example.test" :text "On my way."
                :why "The household asked."}
               (get-in row [:data :input]))
            "the arguments as the caller gave them")
        (is (= {:to "otto@example.test" :text "On my way."}
               (get-in row [:data :forward]))
            "and what the server would receive: no why, because this
             server never asked for one")
        (is (= "to=otto@example.test · text=On my way."
               (get-in row [:data :shown]))
            "R-8: the two fields the entry marks as shown")
        (is (= (.plusSeconds clock-start 86400)
               (get-in row [:data :expires_at]))
            "R-7: 24 hours, stamped at birth")
        (is (str/includes? (:summary row) "emila__send"))
        (is (str/includes? (:summary row) "mail-clerk"))
        (is (str/includes? (:summary row) "otto@example.test"))))

    (testing "an entry that marks no shown fields leaves the line to
              the why"
      (let [out (tool! w "waymark_power"
                       {:tool "emila__wire"
                        :arguments {:amount "40.00"
                                    :why "Otto's half of the deposit."}})
            row (held-row w (str (:held_call (doc-of out))))]
        (is (= "Otto's half of the deposit." (get-in row [:data :shown])))))

    (testing "a tool whose entry says approval none still forwards"
      (let [out (tool! w "waymark_power"
                       {:tool "emila__read" :arguments {:uid "m1"}})]
        (is (false? (:isError out)))
        (is (str/starts-with? (text-of out) "answered read"))
        (is (= 1 (count (calls w))))))))

;; ── 19 · the two walls ──────────────────────────────────────────────

(deftest the-caller-does-not-decide-and-an-approver-does
  (let [w (world)
        {:keys [id]} (send! w)]

    (testing "the caller cannot allow its own call, role or no role"
      (let [e (refused #(inv/invoke! (:eng w) :held_call id :allow {}
                                     {:principal (assoc clerk
                                                        :roles #{"approver"})}))]
        (is (some? e))
        (is (str/includes? (str (ex-message e)) "cannot be the one who allows"))
        (is (= :held (:state (held-row w id))) "and the row did not move"))
      (is (= [] (calls w)) "nothing reached the rig"))

    (testing "a person who is not the caller and holds no role meets
              the second wall"
      (let [e (refused #(inv/invoke! (:eng w) :held_call id :refuse
                                     {:reason "not this one"}
                                     {:principal a-sibling}))]
        (is (some? e))
        (is (str/includes? (str (ex-message e)) "approver role")))
      (is (= :held (:state (held-row w id)))))

    (testing "and a person who holds the role answers it"
      (is (= :done (allow! w id approver)))
      (is (= "mom" (get-in (held-row w id) [:data :decided_by]))))))

;; ── 20 · the allow forwards once, and the refusal forwards nothing ──

(deftest an-allow-forwards-once-and-lands-the-capped-answer
  (let [w (world)
        {:keys [id]} (send! w)]

    (testing "the tap forwards exactly what the door had prepared"
      (is (= :done (allow! w id approver)))
      (is (= 1 (count (calls w))) "once, and once only")
      (let [call (first (calls w))]
        (is (= "send" (get-in call [:params :name]))
            "the server hears its own bare name")
        (is (= {:to "otto@example.test" :text "On my way."}
               (get-in call [:params :arguments])))))

    (testing "and the answer lands on the row for the caller to read"
      (let [row (held-row w id)]
        (is (= :done (:state row)))
        (is (str/includes? (str (get-in row [:data :answer]))
                           "answered send"))
        (is (= 0 (long (or (get-in row [:data :answer_dropped]) 0)))
            "a small answer drops nothing")
        (is (= "mom" (get-in row [:data :decided_by])))
        (is (some? (get-in row [:data :decided_at])))))

    (testing "a second allow is refused, because the row is not held"
      (let [e (refused #(inv/invoke! (:eng w) :held_call id :allow {}
                                     {:principal approver}))]
        (is (some? e)))
      (is (= 1 (count (calls w))) "and the rig was not asked again"))

    (testing "a refusal carries its reason and reaches no wire"
      (let [{id2 :id} (send! w {:to "iris@example.test" :text "Later."})]
        (inv/invoke! (:eng w) :held_call id2 :refuse
                     {:reason "Otto already knows."}
                     {:principal approver})
        (let [row (held-row w id2)]
          (is (= :refused (:state row)))
          (is (= "Otto already knows." (get-in row [:data :reason])))
          (is (= "mom" (get-in row [:data :decided_by]))))
        (is (= 1 (count (calls w))) "still the one call from the allow")))))

(deftest a-large-answer-is-cut-and-the-bytes-that-went-are-counted
  (let [w (world)
        {:keys [id]} (send! w {:to "otto@example.test"
                               :text (apply str (repeat 20000 "x"))})]
    (is (= :done (allow! w id approver)))
    (let [row (held-row w id)
          kept (count (.getBytes ^String (str (get-in row [:data :answer]))
                                 "UTF-8"))]
      (is (= held/answer-cap-bytes kept) "cut at the ceiling")
      (is (pos? (long (get-in row [:data :answer_dropped])))
          "and the bytes that went are counted, so a reader adding the
           two reads what the server said"))))

;; ── 21 · a wire failure after the allow ─────────────────────────────

(deftest a-wire-failure-moves-the-row-to-failed-with-the-reason
  (let [w (world)
        {:keys [id]} (send! w)]
    (reset! (:down? w) true)
    (is (= :failed (allow! w id approver)))
    (let [row (held-row w id)]
      (is (= :failed (:state row)))
      (is (not (str/blank? (str (get-in row [:data :reason]))))
          "the wire's own sentence, for the caller to read")
      (is (nil? (get-in row [:data :answer]))))
    (is (= 1 (count (calls w)))
        "the engine tried exactly once; nothing retries a person's yes")))

;; ── 22 · the seat's wake on its own held call ───────────────────────

(deftest a-seat-wakes-on-its-own-held-call-and-not-anothers
  (let [w (world)
        eng (:eng w)
        {mine :id} (send! w)
        ;; a second caller's call, through the same door
        other (wear! eng other-clerk mail-scope)
        theirs (str (:held_call
                     (doc-of (tool! (assoc w :session (:session other))
                                    "waymark_power"
                                    {:tool "emila__send"
                                     :arguments {:to "x@example.test"
                                                 :text "Hello."
                                                 :why "Because."}}))))
        entry {:kind "held_call" :actions ["allow" "refuse"]
               :filter {:caller "mail-clerk"}}]

    (testing "the entry matches the two verdict doors and no other"
      (is (true? (wakes/matches? entry :held_call :allow)))
      (is (true? (wakes/matches? entry :held_call :refuse)))
      (is (false? (wakes/matches? entry :held_call :expire)))
      (is (false? (wakes/matches? entry :mcp_server :discover))))

    (testing "and the filter on caller picks out this seat's own call"
      (is (true? (wakes/moved-under? eng :held_call mine (:filter entry)))
          "R-6: caller must be filterable, or the seat wakes for
           everybody's calls")
      (is (false? (wakes/moved-under? eng :held_call theirs
                                      (:filter entry)))))))

;; ── 23 · the sweep expires a held call ──────────────────────────────

(deftest the-sweep-expires-a-held-call-and-the-caller-reads-expired
  (let [w (world)
        {:keys [id]} (send! w)]

    (testing "nothing expires before its moment"
      (reset! (:clock w) (.plusSeconds clock-start 3600))
      (is (= 0 (held/sweep-expired! (:eng w))))
      (is (= :held (:state (held-row w id)))))

    (testing "and past it the sweep writes the word the caller reads"
      (reset! (:clock w) (.plusSeconds clock-start 90000))
      (is (= 1 (held/sweep-expired! (:eng w))))
      (is (= :expired (:state (held-row w id))))
      (is (= [] (calls w)) "nothing runs late"))

    (testing "a second pass writes nothing"
      (is (= 0 (held/sweep-expired! (:eng w)))))))

;; ── 24 · the engine's own hand never holds ──────────────────────────

(deftest the-engines-own-call-on-a-person-tool-runs-without-holding
  (let [w (world)
        eng (:eng w)
        answer (servers/call! eng "emila__send"
                              {:to "otto@example.test" :text "On my way."})]
    (is (str/starts-with? (str (get-in answer [:content 0 :text]))
                          "answered send"))
    (is (= 1 (count (calls w))) "the engine's own hand went straight out")
    (is (zero? (long (store/with-tx (:storage eng)
                       (fn [tx] (store/count-matching (:storage eng) tx
                                                      :held_call [])))))
        "R-10: the policy binds callers, and the engine is not a caller")

    (testing "the dispatcher the sources hold is the same hand"
      (let [rpc (gate/rpc-of eng)]
        (rpc "tools/call" {:name "emila__send"
                           :arguments {:to "x@example.test" :text "Hi."}})
        (is (= 2 (count (calls w))))))))

;; ── 25 · the sitting's line, and what waymark_powers says ───────────

(deftest a-held-call-counts-one-served-answer-and-no-refusal
  (let [w (world)
        eng (:eng w)
        seat (:row (inv/create! eng :seat
                                {:name "mail-desk"
                                 :charter "Answer the household's mail."
                                 :scope [{:kind "capability" :actions []}]
                                 :standing_ttl_seconds 604800
                                 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 1000000}
                                {:principal colton}))
        model (:row (inv/create! eng :model
                                 {:name "held-call-test-model"
                                  :display "Held 1"
                                  :vendor "anthropic" :tier "economy"
                                  :price_input_per_mtok 1M
                                  :price_output_per_mtok 5M
                                  :price_cache_read_per_mtok 0.1M
                                  :price_cache_write_per_mtok 1.25M}
                                 {:principal colton}))
        sitting (:row (inv/create! eng :sitting
                                   {:seat (:id seat) :model (:id model)
                                    :grant (:grant-id w)}
                                   {:principal clerk}))
        {:keys [result]} (send! w)
        line (get-in (store/with-tx (:storage eng)
                       (fn [tx] (store/load-row (:storage eng) tx :sitting
                                                (str (:id sitting)) {})))
                     [:data :served :waymark_power])
        row (store/with-tx (:storage eng)
              (fn [tx] (store/load-row (:storage eng) tx :sitting
                                       (str (:id sitting)) {})))]

    (testing "one served answer of its own size, and no refusal"
      (is (= 1 (long (:calls line))))
      (is (= (count (.getBytes ^String (text-of result) "UTF-8"))
             (long (:bytes line)))
          "the bytes the caller actually read")
      (is (zero? (long (or (get-in row [:data :refusals]) 0)))
          "R-2: a held call is never counted as a refusal"))

    (testing "and waymark_powers says the tool waits on a person"
      (let [doc (doc-of (tool! w "waymark_powers" {}))
            send (get-in doc [:actions :emila__send])]
        (is (= "person" (:approval send)))
        (is (str/includes? (str (:description send)) "A person must allow"))
        (is (some? (:held send)))
        (is (true? (get-in send [:why :required]))
            "an approval that holds a call demands the sentence too")))))

;; ── the entry's two spellings ───────────────────────────────────────

(deftest why-true-is-the-older-spelling-of-approval-why
  (testing "an entry that says only why true still demands a sentence
            and still runs at once"
    (let [e {:power "email.send" :tools ["send"] :why true}]
      (is (= :why (servers/approval-of e)))
      (is (true? (servers/why-demanded? e)))
      (is (false? (servers/person-approval? e)))))
  (testing "approval wins where both are written, and person demands
            the sentence too"
    (is (= :person (servers/approval-of {:approval "person" :why true})))
    (is (true? (servers/why-demanded? {:approval "person"})))
    (is (= :none (servers/approval-of {}))))
  (testing "and the two spellings must agree at the write door"
    (is (false? (servers/entry-approval-agrees?
                 {:power "p" :tools ["t"] :why true :approval "none"})))
    (is (false? (servers/entry-approval-agrees?
                 {:power "p" :tools ["t"] :why false :approval "person"})))
    (is (true? (servers/entry-approval-agrees?
                {:power "p" :tools ["t"] :why true :approval "why"})))
    (is (true? (servers/entry-approval-agrees?
                {:power "p" :tools ["t"] :approval "person"})))))

(deftest a-restate-whose-two-spellings-disagree-refuses
  (let [w (world)
        eng (:eng w)
        row (servers/row-by-name eng "emila")
        e (refused #(inv/invoke! eng :mcp_server (str (:id row)) :restate
                                 {:powers [{:power "email.send"
                                            :tools ["send"]
                                            :why true
                                            :approval "none"}]}
                                 {:principal colton}))]
    (is (some? e))
    (is (= 422 (:status (ex-data e)))
        (str "the entry's own place in the refusal, which is malli's and"
             " not a guard's: " (ex-message e)))))

;; ── the why is still demanded before the hold ───────────────────────

(deftest a-person-tool-called-with-no-why-refuses-and-holds-nothing
  (let [w (world)
        out (tool! w "waymark_power"
                   {:tool "emila__send"
                    :arguments {:to "otto@example.test" :text "On my way."}})]
    (is (true? (:isError out)))
    (is (str/includes? (text-of out) "why"))
    (is (= [] (calls w)))
    (is (zero? (long (store/with-tx (:storage (:eng w))
                       (fn [tx] (store/count-matching (:storage (:eng w)) tx
                                                      :held_call [])))))
        "the order is the security property: the why refuses before
         the hold mints anything")))

;; ── the office the call was made in ─────────────────────────────────

(deftest a-held-call-names-the-sitting-the-session-sat-in
  (testing "R-2: the mint writes the sitting the door hands it, and
            writes none when the session sits in no seat"
    (let [w (world)
          eng (:eng w)
          entry {:power "email.send" :tools ["send"] :approval "person"
                 :shown ["to"]}
          sat (held/hold! eng {:tool "emila__send" :entry entry
                               :why "The household asked."
                               :caller "mail-clerk"
                               :input {:to "otto@example.test"}
                               :forward {:to "otto@example.test"}
                               :sitting "sitting-1"})
          loose (held/hold! eng {:tool "emila__send" :entry entry
                                 :why "The household asked."
                                 :caller "colton"
                                 :input {:to "otto@example.test"}
                                 :forward {:to "otto@example.test"}})]
      (is (= "sitting-1" (get-in (:row sat) [:data :sitting])))
      (is (nil? (get-in (:row loose) [:data :sitting]))
          "a person's own hand sits in no seat, and the row says so")
      (is (= "to=otto@example.test" (get-in (:row sat) [:data :shown]))
          "one shown field reads as one pair")
      (is (true? (:held (wire/read-json
                         (get-in (:answer sat) [:content 0 :text]))))))))

;; ── the caller reads its own row with no grant ──────────────────────

(deftest the-caller-reads-its-own-held-call-and-opens-no-door-on-it
  (let [w (world)
        {:keys [id]} (send! w)
        vis (get-in w [:session :visibility])]
    (is (true? ((:kind? vis) :held_call)))
    (is (true? ((:row? vis) :held_call id))
        "R-6: an ask you cannot read the answer to is not an ask")
    (is (false? ((:action? vis) :held_call :allow))
        "and the verdicts stay a person's")))
