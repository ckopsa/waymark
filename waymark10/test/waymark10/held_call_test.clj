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
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp-client :as client]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]
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
        ;; a decoded row carries no summary line; the envelope renders
        ;; it from the kind's template, so render that here
        (let [line (summary/render (:summary (get (inv/resources (:eng w))
                                                   :held_call))
                                   (assoc row :kind :held_call))]
          (is (str/includes? line "emila__send"))
          (is (str/includes? line "mail-clerk"))
          (is (str/includes? line "otto@example.test")))))

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

;; ── R-10.6 · a power door's refusal counts, a wire failure does not ─

(deftest a-power-refusal-counts-one-and-a-dark-wire-counts-none
  (let [{:keys [eng down?] :as w} (world)
        ;; the clerk wearing email.read alone: email.send is a 403
        {:keys [session grant-id]} (wear! eng clerk
                                          [{:kind "email.read" :actions []}])
        narrow (assoc w :session session)
        seat (:row (inv/create! eng :seat
                                {:name "read-desk"
                                 :charter "Read the household's mail."
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
                                    :grant grant-id}
                                   {:principal clerk}))
        refusals (fn []
                   (long (or (get-in (store/with-tx (:storage eng)
                                       (fn [tx] (store/load-row
                                                 (:storage eng) tx :sitting
                                                 (str (:id sitting)) {})))
                                     [:data :refusals])
                             0)))]

    (testing "an ungranted power is a 403, and the sitting counts one"
      (let [out (tool! narrow "waymark_power"
                       {:tool "emila__send"
                        :arguments {:to "otto@example.test" :text "Hi."
                                    :why "The household asked."}})]
        (is (true? (:isError out)))
        (is (= 403 (:status (doc-of out))))
        (is (= 1 (refusals)))
        (is (empty? (calls w)) "a refusal never touches the wire")))

    (testing "a granted call answered adds nothing"
      (let [out (tool! narrow "waymark_power"
                       {:tool "emila__read" :arguments {:uid "7"}})]
        (is (false? (:isError out)))
        (is (= 1 (refusals)))))

    (testing "a dark wire is not the model's refusal, and adds nothing"
      (reset! down? true)
      (let [out (tool! narrow "waymark_power"
                       {:tool "emila__read" :arguments {:uid "8"}})]
        (is (true? (:isError out)))
        (is (= 502 (:status (doc-of out))))
        (is (= 1 (refusals)))))))

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
                                 {:principal colton
                                  ;; the restate is fenced, so the call
                                  ;; hands over the etag an honest client
                                  ;; would have read off the row
                                  :if-match (inv/etag :mcp_server (:id row)
                                                      (:version row))}))]
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
          ;; the ref guard asks that the sitting stands, so one is
          ;; planted raw, past the doors: the test is about the
          ;; stamp and not about how a sitting is born
          _ (store/with-tx (:storage eng)
              (fn [tx]
                (store/insert-row! (:storage eng) tx :sitting
                                   {:id "sitting-1" :state :open :version 1
                                    :data {:seat "seat-1" :model "model-1"
                                           :grant "grant-1"}
                                    :shape (:shape (get (inv/resources eng)
                                                        :sitting) 1)
                                    :owner "mail-clerk"})))
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

;; ── the notifier's fake chat (waymark-fp62.10.3) ──────────────────

(def ^:private chat-tools
  [{:name "send_message" :description "Send a chat message."
    :inputSchema {:type "object"
                  :properties {:chat_id {:type "string"}
                               :text {:type "string"}}}}])

(defn- fake-chat [log down?]
  (fn [method params]
    (swap! log conj {:method method :params params})
    (when (and @down? (= "tools/call" method))
      (throw (client/unreachable "the fake is down.")))
    (case method
      "tools/list" {:tools chat-tools}
      "tools/call" {:content [{:type "text" :text "sent"}] :isError false})))

(defn- chat-world []
  (let [log (atom [])
        down? (atom false)
        fake (fake-chat log down?)
        eng (engine/engine {:storage (memory/storage)
                            :resources [caps/capability]
                            :services {:mcp-servers
                                       {:client-fn (fn [row]
                                                     (when (= "tgrambot"
                                                              (get-in row [:data :name]))
                                                       fake))}}})
        _ (inv/create! eng :capability
                       {:token "chat.send"
                        :description "chat.send through a server row."
                        :enforced_by "this engine's own power door"}
                       {:principal colton})
        server (:row (inv/create! eng :mcp_server
                                  {:name "tgrambot" :transport "http"
                                   :url "http://fake.invalid/mcp/"
                                   :powers [{:power "chat.send"
                                             :tools ["send_message"]
                                             :approval "person"}]}
                                  {:principal colton}))]
    {:eng eng :log log :down? down? :server-id (str (:id server))}))

(defn- chat-notifier! [{:keys [eng server-id]} on]
  (:row (inv/create! eng :notifier
                     {:name "the owner's chat"
                      :server server-id
                      :tool "tgrambot__send_message"
                      :input_template {:chat_id "42"
                                       :text "{kind} {action}: {summary} {link}"}
                      :on on
                      :audience "colton"
                      :link_base "https://work.example.org/"}
                     {:principal colton})))

(defn- drain-notices! [eng]
  (consumers/drain-consumer! eng held/notifier-consumer
                             (held/notifier-consumer-fn eng)))

(defn- chat-sends [log]
  (filterv #(= "tools/call" (:method %)) @log))

(defn- hold-chat! [{:keys [eng server-id]}]
  (:row (held/hold! eng {:server server-id :tool "tgrambot__send_message"
                         :input {:chat_id "7" :text "hi"}
                         :forward {:chat_id "7" :text "hi"}
                         :why "say hello" :caller "mail-clerk"})))

(defn- chat-notifier-data [eng id]
  (:data (store/with-tx (:storage eng)
           #(store/load-row (:storage eng) % :notifier (str id) {}))))

(defn- chat-held-count [eng]
  (store/with-tx (:storage eng)
    #(store/count-matching (:storage eng) % :held_call [])))

(deftest a-notifier-sends-one-message-per-held-call-carrying-the-link
  (let [{:keys [eng log] :as w} (chat-world)
        n (chat-notifier! w [{:kind "held_call" :actions ["create"]}])]
    (drain-notices! eng)
    (let [call (hold-chat! w)]
      (drain-notices! eng)
      (let [s (chat-sends log)
            text (get-in (first s) [:params :arguments :text])]
        (is (= 1 (count s)))
        (is (= "send_message" (get-in (first s) [:params :name])))
        (is (str/includes? (str text)
                           (str "https://work.example.org/api/held_calls/" (:id call))))
        (is (= 1 (:sent (chat-notifier-data eng (:id n)))))))))

(deftest a-failed-notice-counts-and-the-held-call-stands
  (let [{:keys [eng down?] :as w} (chat-world)
        n (chat-notifier! w [{:kind "held_call" :actions ["create"]}])]
    (drain-notices! eng)
    (reset! down? true)
    (let [call (hold-chat! w)]
      (drain-notices! eng)
      (let [data (chat-notifier-data eng (:id n))]
        (is (= 1 (:failed data)))
        (is (not (str/blank? (str (:last_error data))))))
      (is (= :held (:state (store/with-tx (:storage eng)
                             #(store/load-row (:storage eng) % :held_call
                                              (str (:id call)) {})))))
      (is (= 1 (chat-held-count eng))))))

(deftest the-notifiers-send-does-not-hold
  (testing "the tool's power says approval person, and the engine's own send passes"
    (let [{:keys [eng log] :as w} (chat-world)]
      (chat-notifier! w [{:kind "held_call" :actions ["create"]}])
      (drain-notices! eng)
      (hold-chat! w)
      (drain-notices! eng)
      (is (= 1 (count (chat-sends log))))
      (is (= 1 (chat-held-count eng)) "only the call that was held, none for the notice"))))

(deftest a-notifier-on-a-halt-sends-on-a-halt
  (let [{:keys [eng log] :as w} (chat-world)]
    (chat-notifier! w [{:kind "seat" :actions ["mark_halted"]}])
    (held/notice-transition! eng {:id 1 :kind :seat :resource-id "s-1"
                                  :action :mark_halted :actor "waymark10-seats"
                                  :summary "code-seat · halted"})
    (held/notice-transition! eng {:id 2 :kind :seat :resource-id "s-1"
                                  :action :resume :actor "waymark10-seats"})
    (is (= 1 (count (chat-sends log))))))

(deftest the-audiences-own-transition-sends-no-notice
  (let [{:keys [eng log] :as w} (chat-world)]
    (chat-notifier! w [{:kind "seat" :actions ["mark_halted"]}])
    (held/notice-transition! eng {:id 1 :kind :seat :resource-id "s-1"
                                  :action :mark_halted :actor "colton"})
    (is (= [] (chat-sends log)))))

(deftest a-restart-replays-no-notice-already-sent
  (let [{:keys [eng log] :as w} (chat-world)]
    (chat-notifier! w [{:kind "held_call" :actions ["create"]}])
    (drain-notices! eng)
    (hold-chat! w)
    (drain-notices! eng)
    (is (= 1 (count (chat-sends log))))
    (testing "a fresh consumer function, as a restarted engine builds, reads the cursor"
      (drain-notices! eng)
      (is (= 1 (count (chat-sends log)))))))

;; ── the addressed notice (docs/spec-addressed-notice.md) ───────────
;;
;; A notice_rule tells the member a row's ref field names, over the
;; notifier: a small chore kind whose assignee is a ref to member, the
;; same fake chat, and the notifier's own consumer drained by hand.

(defresource notice-chore
  {:kind :chore
   :plural "chores"
   :states [:backlog :active]
   :initial :backlog
   :terminal #{:active}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name {:x-display {:label "Name"}} [:string {:min 1 :max 120}]]
            [:assignee {:optional true :kind :member
                        :x-display {:label "Assigned to"}}
             [:maybe :waymark/ref]]]
   :actions {:queue {:from #{:backlog} :to :active
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "A queued chore stays queued in this rig."}
                     :display {:label "Queue"}}}})

(defhandler retime-block [row inp _ctx]
  (assoc-in row [:data :starts_at] (:starts_at inp)))

;; A block names its own instant and its member directly: the timed
;; notice's rig, with a fake clock.
(defresource at-block
  {:kind :block
   :plural "blocks"
   :states [:planned :skipped]
   :initial :planned
   :terminal #{:skipped}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name {:x-display {:label "Name"}} [:string {:min 1 :max 120}]]
            [:owner {:optional true :kind :member
                     :x-display {:label "Whose"}}
             [:maybe :waymark/ref]]
            [:starts_at {:x-display {:label "Starts at"}} :waymark/instant]]
   :actions {:retime {:from #{:planned} :to :planned
                      :input [:map [:starts_at :waymark/instant]]
                      :safety {:idempotent true :reversible true :confirm false}
                      :handler retime-block
                      :display {:label "Retime"}}
             :skip {:from #{:planned} :to :skipped
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "A skipped block stays skipped in this rig."}
                    :display {:label "Skip"}}}})

;; two hops: a step names its plan, and the plan names the member
(defresource notice-plan
  {:kind :notice_plan
   :plural "notice_plans"
   :states [:backlog :active]
   :initial :backlog
   :terminal #{:active}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name {:x-display {:label "Name"}} [:string {:min 1 :max 120}]]
            [:member {:optional true :kind :member
                      :x-display {:label "Whose plan"}}
             [:maybe :waymark/ref]]]
   :actions {:queue {:from #{:backlog} :to :active
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "A queued plan stays queued in this rig."}
                     :display {:label "Queue"}}}})

(defresource notice-step
  {:kind :notice_step
   :plural "notice_steps"
   :states [:backlog :active]
   :initial :backlog
   :terminal #{:active}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name {:x-display {:label "Name"}} [:string {:min 1 :max 120}]]
            [:plan_id {:optional true :kind :notice_plan
                       :x-display {:label "Plan"}}
             [:maybe :waymark/ref]]]
   :actions {:queue {:from #{:backlog} :to :active
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "A queued step stays queued in this rig."}
                     :display {:label "Queue"}}}})

(defn- notice-engine [storage log clock & [gate]]
  (let [chat (fake-chat log (atom false))
        ;; `gate`, when given, runs before each send: a send that waits
        fake (fn [method params]
               (when (and gate (= "tools/call" method)) (gate))
               (chat method params))]
    (engine/engine (cond-> {:storage storage
                            :resources [caps/capability notice-chore at-block
                                        notice-plan notice-step]
                            :services {:mcp-servers
                                       {:client-fn (fn [row]
                                                     (when (= "tgrambot"
                                                              (get-in row [:data :name]))
                                                       fake))}}}
                     clock (assoc :now-fn #(deref clock))))))

(defn- notice-world [& [clock gate]]
  (let [log (atom [])
        storage (memory/storage)
        eng (notice-engine storage log clock gate)
        _ (inv/create! eng :capability
                       {:token "chat.send"
                        :description "chat.send through a server row."
                        :enforced_by "this engine's own power door"}
                       {:principal colton})
        server (:row (inv/create! eng :mcp_server
                                  {:name "tgrambot" :transport "http"
                                   :url "http://fake.invalid/mcp/"
                                   :powers [{:power "chat.send"
                                             :tools ["send_message"]
                                             :approval "person"}]}
                                  {:principal colton}))
        notifier (:row (inv/create! eng :notifier
                                    {:name "the house chat"
                                     :server (str (:id server))
                                     :tool "tgrambot__send_message"
                                     :input_template {:chat_id "0"
                                                      :text "{kind} {action}: {summary}"}
                                     :on [{:kind "nothing_here"}]
                                     :audience "colton"
                                     :link_base "https://work.example.org/"}
                                    {:principal colton}))]
    {:eng eng :log log :storage storage :clock clock
     :notifier-id (str (:id notifier))}))

(defn- notice-member! [{:keys [eng notifier-id]} display notify?]
  (let [m (:row (inv/create! eng :member {:display display :actor_type "human"}
                             {:principal colton}))]
    ;; how a member is reached is their own hand: the member sets it
    (when notify?
      (inv/invoke! eng :member (str (:id m)) :set_notify
                   {:notify {:notifier notifier-id :input {:chat_id "42"}}}
                   {:principal (t/principal {:id (str (:id m))
                                             :display display})
                    :if-match (inv/etag :member (:id m) (:version m))}))
    (str (:id m))))

(defn- notice-rule! [{:keys [eng notifier-id]} field]
  (:row (inv/create! eng :notice_rule
                     {:name "tell the assignee"
                      :kind "chore"
                      :when {:to_state "active"}
                      :address {:field field}
                      :notifier notifier-id}
                     {:principal colton})))

(defn- notice-rule-data [eng id]
  (:data (store/with-tx (:storage eng)
           #(store/load-row (:storage eng) % :notice_rule (str id) {}))))

(defn- notice-chore! [eng assignee]
  (:row (inv/create! eng :chore (cond-> {:name "the dishes"}
                                  assignee (assoc :assignee assignee))
                     {:principal colton})))

(defn- queue-chore! [eng chore who]
  (inv/invoke! eng :chore (str (:id chore)) :queue {} {:principal who}))

(deftest a-notice-rule-tells-the-assignee-through-their-channel-with-the-link
  (let [{:keys [eng log] :as w} (notice-world)
        jack (notice-member! w "Jack" true)
        r (notice-rule! w "assignee")
        c (notice-chore! eng jack)]
    (drain-notices! eng)
    (queue-chore! eng c colton)
    (drain-notices! eng)
    (let [s (chat-sends log)
          args (get-in (first s) [:params :arguments])]
      (is (= 1 (count s)))
      (is (= "send_message" (get-in (first s) [:params :name])))
      (is (= "42" (str (:chat_id args))) "the member says where")
      (is (str/includes? (str (:text args))
                         (str "https://work.example.org/api/chores/" (:id c))))
      (is (= 1 (:sent (notice-rule-data eng (:id r))))))))

(deftest the-assignee-who-moved-the-row-is-told-nothing
  (let [{:keys [eng log] :as w} (notice-world)
        jack (notice-member! w "Jack" true)
        _ (notice-rule! w "assignee")
        c (notice-chore! eng jack)]
    (drain-notices! eng)
    (queue-chore! eng c (t/principal {:id jack :display "Jack"}))
    (drain-notices! eng)
    (is (= [] (chat-sends log)))))

(deftest a-notice-rule-naming-a-non-ref-or-unknown-field-refuses-with-a-sentence
  (let [w (notice-world)]
    (doseq [field ["name" "nowhere"]]
      (testing field
        (let [e (refused #(notice-rule! w field))]
          (is (some? e))
          (is (re-find #"not a ref field|address-names-a-member"
                       (str (ex-message e) " " (pr-str (ex-data e))))))))))

(deftest a-member-without-notify-is-skipped-and-counted
  (let [{:keys [eng log] :as w} (notice-world)
        jill (notice-member! w "Jill" false)
        r (notice-rule! w "assignee")]
    (drain-notices! eng)
    (queue-chore! eng (notice-chore! eng jill) colton)
    (queue-chore! eng (notice-chore! eng nil) colton)
    (drain-notices! eng)
    (is (= [] (chat-sends log)))
    (let [data (notice-rule-data eng (:id r))]
      (is (= 1 (:skipped data)))
      (is (= 1 (:unaddressed data)) "an unassigned chore notifies nobody"))))

;; ── the timed notice ────────────────────────────────────────────────

(defn- instant [s] (java.time.Instant/parse s))

(defn- at-world []
  (notice-world (atom (instant "2026-09-29T09:00:00Z"))))

(defn- at-rule! [{:keys [eng notifier-id]}]
  (:row (inv/create! eng :notice_rule
                     {:name "tell at the start"
                      :kind "block"
                      :when {:to_state "planned"}
                      :at {:field "starts_at"}
                      :address {:field "owner"}
                      :notifier notifier-id}
                     {:principal colton})))

(deftest an-at-rule-naming-a-missing-or-non-datetime-field-refuses-with-a-sentence
  (let [{:keys [eng notifier-id]} (at-world)]
    (doseq [field ["name" "nowhere"]]
      (testing field
        (let [e (refused #(inv/create! eng :notice_rule
                                       {:name "tell at the start"
                                        :kind "block"
                                        :when {:to_state "planned"}
                                        :at {:field field}
                                        :address {:field "owner"}
                                        :notifier notifier-id}
                                       {:principal colton}))]
          (is (some? e))
          (is (re-find #"not a datetime|not a field|at-names-a-datetime"
                       (str (ex-message e) " " (pr-str (ex-data e))))))))))

(defn- at-block! [eng owner starts-at]
  (:row (inv/create! eng :block {:name "focus" :owner owner :starts_at starts-at}
                     {:principal colton})))

(deftest an-at-rule-tells-once-on-the-first-sweep-at-or-after-the-instant
  (let [{:keys [eng log clock] :as w} (at-world)
        jack (notice-member! w "Jack" true)
        r (at-rule! w)
        b (at-block! eng jack "2026-09-29T10:00:00Z")]
    (drain-notices! eng)
    (is (= [] (chat-sends log)) "an at rule hears no transition")
    (is (= 0 (held/sweep-notice-instants! eng)) "not yet")
    (reset! clock (instant "2026-09-29T10:00:00Z"))
    (is (= 1 (held/sweep-notice-instants! eng)))
    (reset! clock (instant "2026-09-29T10:05:00Z"))
    (is (= 0 (held/sweep-notice-instants! eng)) "once")
    (let [s (chat-sends log)]
      (is (= 1 (count s)))
      (is (= "42" (str (get-in (first s) [:params :arguments :chat_id]))))
      (is (str/includes? (str (get-in (first s) [:params :arguments :text]))
                         (str "https://work.example.org/api/blocks/" (:id b)))))
    (is (= 1 (:sent (notice-rule-data eng (:id r)))))))

(deftest an-at-rule-stored-before-the-wall-reports-its-field-once
  (let [{:keys [eng log clock storage] :as w} (at-world)
        jack (notice-member! w "Jack" true)
        r (at-rule! w)
        id (str (:id r))
        _ (at-block! eng jack "2026-09-29T10:00:00Z")]
    ;; a row the wall never judged: its `at` names a string field
    (store/with-tx storage
      (fn [tx]
        (let [raw (store/load-row storage tx :notice_rule id {:for-update true})]
          (store/update-data! storage tx :notice_rule id
                              (assoc (:data raw) :at {:field "name"})
                              (:next-flip-at raw)))))
    (reset! clock (instant "2026-09-29T10:30:00Z"))
    (is (= 0 (held/sweep-notice-instants! eng)))
    (is (= 0 (held/sweep-notice-instants! eng)))
    (is (= [] (chat-sends log)))
    (let [data (notice-rule-data eng id)]
      (is (re-find #"block\.name is not a datetime" (str (:last_error data))))
      (is (= 1 (:failed data)) "counted once, not once a sweep"))))

(deftest an-at-rule-whose-kind-is-no-longer-served-reports-it-once
  (let [{:keys [eng log clock storage] :as w} (at-world)
        jack (notice-member! w "Jack" true)
        r (at-rule! w)
        id (str (:id r))
        _ (at-block! eng jack "2026-09-29T10:00:00Z")]
    ;; a row whose kind the engine stopped serving after it was stored
    (store/with-tx storage
      (fn [tx]
        (let [raw (store/load-row storage tx :notice_rule id {:for-update true})]
          (store/update-data! storage tx :notice_rule id
                              (assoc (:data raw) :kind "retired_block")
                              (:next-flip-at raw)))))
    (reset! clock (instant "2026-09-29T10:30:00Z"))
    (is (= 0 (held/sweep-notice-instants! eng)))
    (is (= 0 (held/sweep-notice-instants! eng)))
    (is (= [] (chat-sends log)))
    (let [data (notice-rule-data eng id)]
      (is (re-find #"retired_block is not a kind this engine serves" (str (:last_error data))))
      (is (= 1 (:failed data)) "counted once, not once a sweep"))))

(deftest editing-the-instant-moves-the-notice
  (let [{:keys [eng log clock] :as w} (at-world)
        jack (notice-member! w "Jack" true)
        _ (at-rule! w)
        b (at-block! eng jack "2026-09-29T10:00:00Z")]
    (inv/invoke! eng :block (str (:id b)) :retime {:starts_at "2026-09-29T11:00:00Z"}
                 {:principal colton})
    (reset! clock (instant "2026-09-29T10:30:00Z"))
    (is (= 0 (held/sweep-notice-instants! eng)) "the old instant is gone")
    (reset! clock (instant "2026-09-29T11:00:00Z"))
    (is (= 1 (held/sweep-notice-instants! eng)) "the new instant tells")
    (is (= 1 (count (chat-sends log))))))

(deftest leaving-the-when-state-first-cancels-the-notice
  (let [{:keys [eng log clock] :as w} (at-world)
        jack (notice-member! w "Jack" true)
        _ (at-rule! w)
        b (at-block! eng jack "2026-09-29T10:00:00Z")]
    (inv/invoke! eng :block (str (:id b)) :skip {} {:principal colton})
    (drain-notices! eng)
    (reset! clock (instant "2026-09-29T10:30:00Z"))
    (is (= 0 (held/sweep-notice-instants! eng)))
    (is (= [] (chat-sends log)))))

(deftest a-restart-between-sweeps-neither-loses-nor-doubles-a-notice
  (let [{:keys [eng log clock storage] :as w} (at-world)
        jack (notice-member! w "Jack" true)
        _ (at-rule! w)
        _ (at-block! eng jack "2026-09-29T10:00:00Z")
        _ (at-block! eng jack "2026-09-29T11:00:00Z")]
    (reset! clock (instant "2026-09-29T10:30:00Z"))
    (is (= 1 (held/sweep-notice-instants! eng)))
    (let [eng2 (notice-engine storage log clock)]
      (is (= 0 (held/sweep-notice-instants! eng2)) "the first is not told again")
      (reset! clock (instant "2026-09-29T11:30:00Z"))
      (is (= 1 (held/sweep-notice-instants! eng2)) "the second, due while down, is told")
      (is (= 2 (count (chat-sends log)))))))

(deftest a-due-set-past-both-caps-is-told-once-each
  (let [{:keys [eng log clock] :as w} (at-world)
        jack (notice-member! w "Jack" true)
        r (at-rule! w)
        n (inc (max @#'held/sweep-cap @#'held/told-cap))]
    (dotimes [_ n] (at-block! eng jack "2026-09-29T10:00:00Z"))
    (reset! clock (instant "2026-09-29T10:00:00Z"))
    (is (= n (held/sweep-notice-instants! eng)) "every due row, past one page")
    (is (= 0 (held/sweep-notice-instants! eng)) "none twice, past the mark cap")
    (is (= n (count (chat-sends log))))
    (let [data (notice-rule-data eng (:id r))]
      (is (= n (:sent data)))
      (is (= n (count (:told data))) "a mark on a row still due is kept"))))

(deftest a-slow-send-does-not-hold-the-rules-lock
  (let [entered (promise)
        release (promise)
        {:keys [eng storage clock] :as w}
        (notice-world (atom (instant "2026-09-29T09:00:00Z"))
                      (fn [] (deliver entered true) @release))
        jack (notice-member! w "Jack" true)
        r (at-rule! w)
        _ (at-block! eng jack "2026-09-29T10:00:00Z")
        _ (reset! clock (instant "2026-09-29T10:00:00Z"))
        sweep (future (held/sweep-notice-instants! eng))]
    (try
      (is (true? (deref entered 5000 false)) "the send has begun")
      (let [locked (future
                     (store/with-tx storage
                       #(some? (store/load-row storage % :notice_rule (str (:id r))
                                               {:for-update true}))))]
        (is (true? (deref locked 5000 :held)) "the rule's row is free while the send waits"))
      (is (= 1 (count (:told (notice-rule-data eng (:id r)))))
          "the mark is claimed before the send")
      (finally (deliver release true)))
    (is (= 1 (deref sweep 5000 :stuck)))
    (is (= 1 (:sent (notice-rule-data eng (:id r)))) "the count lands after")))

(defn- step-rule! [{:keys [eng notifier-id]} address]
  (:row (inv/create! eng :notice_rule
                     {:name "tell the plan's member"
                      :kind "notice_step"
                      :when {:to_state "active"}
                      :address address
                      :notifier notifier-id}
                     {:principal colton})))

(deftest a-notice-rule-tells-the-member-a-ref-chain-names
  (let [{:keys [eng log] :as w} (notice-world)
        jack (notice-member! w "Jack" true)
        r (step-rule! w {:field "plan_id" :then "member"})
        plan (:row (inv/create! eng :notice_plan {:name "monday" :member jack}
                                {:principal colton}))
        step! #(:row (inv/create! eng :notice_step
                                  (cond-> {:name "the dishes"}
                                    % (assoc :plan_id %))
                                  {:principal colton}))
        s (step! (str (:id plan)))]
    (drain-notices! eng)
    (inv/invoke! eng :notice_step (str (:id s)) :queue {} {:principal colton})
    (inv/invoke! eng :notice_step (str (:id (step! nil))) :queue {} {:principal colton})
    (drain-notices! eng)
    (let [sends (chat-sends log)]
      (is (= 1 (count sends)))
      (is (= "42" (str (get-in (first sends) [:params :arguments :chat_id])))
          "the plan's member says where"))
    (let [data (notice-rule-data eng (:id r))]
      (is (= 1 (:sent data)))
      (is (= 1 (:unaddressed data)) "a step with no plan notifies nobody"))))

(deftest a-ref-chain-judges-every-hop
  (let [w (notice-world)]
    (doseq [address [{:field "plan_id"}
                     {:field "plan_id" :then "name"}
                     {:field "plan_id" :then "nowhere"}
                     {:field "name" :then "member"}]]
      (testing (pr-str address)
        (let [e (refused #(step-rule! w address))]
          (is (some? e))
          (is (re-find #"not a member|not a ref field|address-names-a-member"
                       (str (ex-message e) " " (pr-str (ex-data e))))))))))

;; ── waits_on: the person a held call or an ask waits on ─────────────

(defn- waits-on-rule! [{:keys [eng notifier-id]} kind to-state]
  (:row (inv/create! eng :notice_rule
                     {:name (str "tell whom the " kind " waits on")
                      :kind kind
                      :when {:to_state to-state}
                      :address {:field "waits_on"}
                      :notifier notifier-id}
                     {:principal colton})))

(defn- held-for! [eng chore caller owner]
  (held/hold-door! eng (cond-> {:kind "chore" :action "queue"
                                :id (str (:id chore)) :body {}
                                :caller caller :why "Queue the dishes."}
                         owner (assoc :owner owner))))

(deftest a-held-call-a-seat-made-tells-its-person-with-the-ui-link
  (let [{:keys [eng log] :as w} (notice-world)
        eng (assoc-in eng [:services :transcripts :public-origin]
                      "https://ui.example.org/")
        jack (notice-member! w "Jack" true)
        _ (members/ensure-sitter! eng "seat:dishes" "dishes" jack)
        r (waits-on-rule! w "held_call" "held")
        c (notice-chore! eng nil)]
    (drain-notices! eng)
    (let [h (held-for! eng c "seat:dishes" nil)]
      (is (= jack (get-in h [:data :waits_on])) "the seat's person")
      (drain-notices! eng)
      (let [s (chat-sends log)
            text (str (get-in (first s) [:params :arguments :text]))]
        (is (= 1 (count s)))
        (is (str/includes? text (str (get-in h [:data :shown])))
            "the call's sentence")
        (is (str/includes? text (str "https://ui.example.org/#/api/held_calls/"
                                     (:id h)))
            "the UI's page for the row")
        (is (= 1 (:sent (notice-rule-data eng (:id r)))))))))

(deftest a-held-call-its-own-person-caused-tells-nobody
  (let [{:keys [eng log] :as w} (notice-world)
        jack (notice-member! w "Jack" true)
        _ (waits-on-rule! w "held_call" "held")
        c (notice-chore! eng nil)]
    (drain-notices! eng)
    (is (= jack (get-in (held-for! eng c jack nil) [:data :waits_on])))
    (drain-notices! eng)
    (is (= [] (chat-sends log)))))

(deftest a-person-without-notify-is-skipped-and-counted
  (let [{:keys [eng log] :as w} (notice-world)
        jill (notice-member! w "Jill" false)
        _ (members/ensure-sitter! eng "seat:dishes" "dishes" jill)
        r (waits-on-rule! w "held_call" "held")
        c (notice-chore! eng nil)]
    (drain-notices! eng)
    (held-for! eng c "seat:dishes" nil)
    (drain-notices! eng)
    (is (= [] (chat-sends log)))
    (is (= 1 (:skipped (notice-rule-data eng (:id r)))))))

(deftest an-ask-a-seat-files-tells-the-person-who-approves
  (let [{:keys [eng log] :as w} (notice-world)
        jack (notice-member! w "Jack" true)
        jill (notice-member! w "Jill" true)
        _ (members/ensure-sitter! eng "seat:dishes" "dishes" jack)
        r (waits-on-rule! w "approval_request" "offered")
        _ (drain-notices! eng)
        sitter (assoc (t/principal {:id "seat:dishes" :type :agent})
                      :acts-for jack)
        ask (:row (inv/create! eng :approval_request
                               {:task "Queue the week's chores."
                                :waits_on jill
                                :scope [{:kind "chore" :actions ["queue"]}]}
                               {:principal sitter}))]
    (is (= jack (get-in ask [:data :waits_on]))
        "the engine stamps the person; the body's value is dropped")
    (drain-notices! eng)
    (let [s (chat-sends log)]
      (is (= 1 (count s)))
      (is (str/includes? (str (get-in (first s) [:params :arguments :text]))
                         (str "https://work.example.org/api/approval_requests/"
                              (:id ask)))
          "no public origin: the notifier's link_base and the API path"))
    (is (= 1 (:sent (notice-rule-data eng (:id r)))))))

;; ── quiet hours ─────────────────────────────────────────────────────

(defn- quiet-member! [{:keys [eng notifier-id]} display]
  (let [m (:row (inv/create! eng :member {:display display :actor_type "human"}
                             {:principal colton}))]
    (inv/invoke! eng :member (str (:id m)) :set_notify
                 {:notify {:notifier notifier-id :input {:chat_id "42"}
                           :quiet {:from "22:00" :to "07:00" :zone "UTC"}}}
                 {:principal (t/principal {:id (str (:id m)) :display display})
                  :if-match (inv/etag :member (:id m) (:version m))})
    (str (:id m))))

(deftest a-quiet-window-is-read-in-its-zone-and-may-span-midnight
  (let [night {:quiet {:from "22:00" :to "07:00" :zone "UTC"}}
        day {:quiet {:from "09:00" :to "17:00" :zone "America/Denver"}}]
    (is (held/quiet? night (instant "2026-09-29T23:30:00Z")))
    (is (held/quiet? night (instant "2026-09-30T06:59:00Z")))
    (is (not (held/quiet? night (instant "2026-09-30T07:00:00Z"))) "the end is open")
    (is (not (held/quiet? night (instant "2026-09-29T12:00:00Z"))))
    (is (held/quiet? day (instant "2026-09-29T16:00:00Z")) "10:00 in Denver")
    (is (not (held/quiet? day (instant "2026-09-29T08:00:00Z"))) "02:00 in Denver")
    (is (not (held/quiet? {} (instant "2026-09-29T23:30:00Z"))) "no window, never quiet")))

(deftest quiet-hours-hold-three-notices-and-send-one-digest-when-the-window-closes
  (let [{:keys [eng log clock] :as w} (notice-world (atom (instant "2026-09-29T23:00:00Z")))
        jack (quiet-member! w "Jack")
        r (notice-rule! w "assignee")
        chores (vec (repeatedly 3 #(notice-chore! eng jack)))]
    (drain-notices! eng)
    (doseq [c chores] (queue-chore! eng c colton))
    (drain-notices! eng)
    (is (= [] (chat-sends log)) "nothing is sent inside the window")
    (is (= 3 (:held (notice-rule-data eng (:id r)))))
    (is (= 0 (held/sweep-quiet-digests! eng)) "the window is still open")
    (reset! clock (instant "2026-09-30T07:00:00Z"))
    (is (= 1 (held/sweep-quiet-digests! eng)))
    (is (= 0 (held/sweep-quiet-digests! eng)) "once")
    (let [s (chat-sends log)
          args (get-in (first s) [:params :arguments])]
      (is (= 1 (count s)) "one digest")
      (is (= "42" (str (:chat_id args))) "the member says where")
      (is (str/starts-with? (str (:text args)) "3 notices"))
      (doseq [c chores]
        (is (str/includes? (str (:text args))
                           (str "https://work.example.org/api/chores/" (:id c))))))))

(defn- member-data [eng id]
  (:data (store/with-tx (:storage eng)
           #(store/load-row (:storage eng) % :member (str id) {}))))

(deftest a-failed-digest-keeps-its-lines-and-sends-them-on-the-next-sweep
  (let [down? (atom false)
        {:keys [eng log clock] :as w}
        (notice-world (atom (instant "2026-09-29T23:00:00Z"))
                      #(when @down? (throw (ex-info "the chat is down" {}))))
        jack (quiet-member! w "Jack")
        _ (notice-rule! w "assignee")
        chores (vec (repeatedly 2 #(notice-chore! eng jack)))]
    (drain-notices! eng)
    (doseq [c chores] (queue-chore! eng c colton))
    (drain-notices! eng)
    (reset! down? true)
    (reset! clock (instant "2026-09-30T07:00:00Z"))
    (is (= 0 (held/sweep-quiet-digests! eng)) "the send failed")
    (let [data (member-data eng jack)]
      (is (= 2 (count (:quiet_held data))) "the lines are put back")
      (is (= 1 (:quiet_digest_failed data)) "the failure is counted")
      (is (seq (:quiet_digest_error data)) "and says why"))
    (reset! down? false)
    (reset! clock (instant "2026-09-30T07:05:00Z"))
    (is (= 1 (held/sweep-quiet-digests! eng)) "the next sweep sends them")
    (is (empty? (:quiet_held (member-data eng jack))))
    (let [s (chat-sends log)]
      (is (= 1 (count s)) "one digest")
      (is (str/starts-with? (str (get-in (first s) [:params :arguments :text]))
                            "2 notices")))))

(defn- orphan-notify!
  "Point the member's notify at a notifier row that is not there."
  [eng id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (let [raw (store/load-row st tx :member (str id) {:for-update true})]
          (store/update-data! st tx :member (str id)
                              (assoc-in (:data raw) [:notify :notifier]
                                        (str (random-uuid)))
                              (:next-flip-at raw)))))))

(deftest a-quiet-member-with-no-carrier-is-skipped-not-failed
  (let [{:keys [eng log clock] :as w} (notice-world (atom (instant "2026-09-29T23:00:00Z")))
        jack (quiet-member! w "Jack")
        _ (notice-rule! w "assignee")
        chores (vec (repeatedly 2 #(notice-chore! eng jack)))]
    (drain-notices! eng)
    (doseq [c chores] (queue-chore! eng c colton))
    (drain-notices! eng)
    (orphan-notify! eng jack)
    (reset! clock (instant "2026-09-30T07:00:00Z"))
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (is (= 0 (held/sweep-quiet-digests! eng)))
        (reset! clock (instant "2026-09-30T07:05:00Z"))
        (is (= 0 (held/sweep-quiet-digests! eng))))
      (is (not (str/includes? (str err) "digest")) "no warning"))
    (let [data (member-data eng jack)]
      (is (= 2 (count (:quiet_held data))) "the lines stay queued")
      (is (nil? (:quiet_digest_failed data)) "no carrier is not a failure")
      (is (nil? (:quiet_digest_error data)))
      (is (= "no carrier" (:quiet_digest_carrier data)) "the roster says so"))
    (is (= [] (chat-sends log)))))

(deftest a-send-that-errors-counts-once-per-sweep
  (let [{:keys [eng clock] :as w}
        (notice-world (atom (instant "2026-09-29T23:00:00Z"))
                      #(throw (ex-info "the chat is down" {})))
        jack (quiet-member! w "Jack")
        _ (notice-rule! w "assignee")
        c (notice-chore! eng jack)]
    (drain-notices! eng)
    (queue-chore! eng c colton)
    (drain-notices! eng)
    (reset! clock (instant "2026-09-30T07:00:00Z"))
    (binding [*err* (java.io.StringWriter.)]
      (is (= 0 (held/sweep-quiet-digests! eng)))
      (is (= 1 (:quiet_digest_failed (member-data eng jack))))
      (reset! clock (instant "2026-09-30T07:05:00Z"))
      (is (= 0 (held/sweep-quiet-digests! eng)))
      (is (= 2 (:quiet_digest_failed (member-data eng jack)))))
    (is (= 1 (count (:quiet_held (member-data eng jack)))))))

(deftest a-notice-outside-the-quiet-window-sends-at-once
  (let [{:keys [eng log] :as w} (notice-world (atom (instant "2026-09-29T12:00:00Z")))
        jack (quiet-member! w "Jack")
        r (notice-rule! w "assignee")
        c (notice-chore! eng jack)]
    (drain-notices! eng)
    (queue-chore! eng c colton)
    (drain-notices! eng)
    (is (= 1 (count (chat-sends log))))
    (is (= 1 (:sent (notice-rule-data eng (:id r)))))
    (is (= 0 (held/sweep-quiet-digests! eng)) "nothing held, no digest")))
