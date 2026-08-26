(ns waymark10.gate-proxy-test
  "The Gate hypermedia proxy (waymark-q95): the door that holds the
  leash and stores nothing.

  The acceptance, proven here against a STUBBED Gate — a fake rpc
  standing where the streamable-HTTP MCP client would speak, so no
  test depends on a live Gate on the household LAN:

  • the affordance document shows ONLY what the presented grant
    admits — a grant with email.read sees the read links and neither
    the send form nor the move form;
  • an ungranted invoke is refused (403/404) IN-PROCESS and never
    reaches Gate — the fake's call log is the proof;
  • a granted read returns Gate's payload, and a granted send
    forwards (with `why` translated to Gate's `__why`) and returns
    Gate's payload verbatim;
  • no rows are written — nothing is mirrored or stored.

  The in-memory twin hosts it, feed_test's own arrangement: the
  capability registry rides the app's resources (:app-opt-in), the
  tokens are minted as ROWS, and the grant/accept/wear loop is the
  ordinary machinery, un-special-cased."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

;; ── the stubbed Gate ────────────────────────────────────────────────

(def ^:private fake-tools
  "What the fake Gate's tools/list serves: the emila reads with an
  OPTIONAL __why (Gate's allow policy on the wire), the mutations
  with a REQUIRED one (its require_approval policy on the wire), and
  one gsd tool the map deliberately does not carry — proof that a
  tool outside the map does not exist through the door whatever Gate
  serves."
  [{:name "emila__inbox"
    :description "List recent inbox messages."
    :inputSchema {:type "object"
                  :properties {:folder {:type "string"}
                               :__why {:type "string"
                                       :description "Optional rationale."}}}}
   {:name "emila__search"
    :description "Search messages."
    :inputSchema {:type "object"
                  :properties {:query {:type "string"}
                               :__why {:type "string"}}
                  :required ["query"]}}
   {:name "emila__send"
    :description "Send an email."
    :inputSchema {:type "object"
                  :properties {:to {:type "string"}
                               :subject {:type "string"}
                               :body {:type "string"}
                               :__why {:type "string"
                                       :description "Required rationale."}}
                  :required ["to" "subject" "__why"]}}
   {:name "emila__move"
    :description "Move messages to a folder."
    :inputSchema {:type "object"
                  :properties {:uids {:type "array"}
                               :destination {:type "string"}
                               :__why {:type "string"}}
                  :required ["uids" "destination" "__why"]}}
   {:name "gsd__agenda"
    :description "Not in the map — never projected."
    :inputSchema {:type "object" :properties {}}}])

(defn- fake-gate
  "An rpc caller shaped exactly as gate-proxy/http-rpc's answer —
  (fn [method params]) → the JSON-RPC :result — that records every
  call it is asked to make. The log IS the wire: an entry here is a
  request that reached Gate, and the never-reaches-Gate half of the
  acceptance is its absence."
  [log]
  (fn [method params]
    (swap! log conj {:method method :params params})
    (case method
      "tools/list" {:tools fake-tools}
      "tools/call" {:content [{:type "text"
                               :text (str "gate answered " (:name params))}]
                    :isError false})))

;; ── the world ───────────────────────────────────────────────────────

(defn- call!
  "One request through the real handler — wrap-identity, the router,
  the problem boundary, everything. Headers are the caller's WHOLE
  identity: none passed is the anonymous knock."
  [eng method uri & {:keys [body query headers]}]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method method :uri uri
                       :headers (merge {"content-type" "application/json"}
                                       headers)}
                query (assoc :query-string query)
                body (assoc :body (wire/write-json body))))]
    (assoc resp :doc (some-> (:body resp) wire/read-json))))

(def ^:private as-mom {"x-waymark-principal" "mom"})
(def ^:private as-claude {"x-waymark-principal" "claude"
                          "x-waymark-actor-type" "agent"})

(defn- boot [log]
  (engine/engine {:storage (memory/storage)
                  ;; the capability registry rides the app's own
                  ;; resources (:app-opt-in), exactly as workqueue10
                  ;; declares it
                  :resources [caps/capability]
                  ;; the test seam gate-proxy/rpc-of reads FIRST — no
                  ;; URL, no socket, no live Gate anywhere in this
                  ;; namespace
                  :gate {:rpc (fake-gate log)}}))

(defn- mint-capabilities!
  "The three email tokens as ROWS — a scope entry wearing a dot is
  judged against the registry, so the vocabulary must exist before a
  grant can name it (workqueue10's boot seed does this in
  deployment)."
  [eng]
  (doseq [[token description] [["email.read" "Read email through Gate."]
                               ["email.send" "Send email through Gate."]
                               ["email.move" "File email through Gate."]]]
    (let [r (call! eng :post "/api/capabilities"
                   :headers as-mom
                   :body {:token token :description description
                          :enforced_by "this engine's own gate door"})]
      (is (= 201 (:status r)) (pr-str (:doc r))))))

(defn- wear!
  "Mint a grant for claude over `scope`, accept it as claude, and
  answer the headers claude wears presenting it."
  [eng scope]
  (let [minted (call! eng :post "/api/grants"
                      :headers as-mom
                      :body {:audience "claude" :scope scope})
        _ (is (= 201 (:status minted)) (pr-str (:doc minted)))
        gid (last (str/split (str (get-in minted [:doc :self])) #"/"))
        accepted (call! eng :post (str "/api/grants/" gid "/-/accept")
                        :headers as-claude)]
    (is (= 200 (:status accepted)) (pr-str (:doc accepted)))
    (assoc as-claude "x-waymark-grant" gid)))

(defn- row-census
  "Every kind's row count, over the storage protocol — the
  nothing-is-stored half of the acceptance is this map not moving."
  [eng]
  (into (sorted-map)
        (map (fn [k]
               [k (count (store/with-tx (:storage eng)
                           (fn [tx] (store/query-rows (:storage eng) tx k
                                                      {} {:limit 1000}))))]))
        (keys (inv/resources eng))))

(defn- gate-calls [log] (filterv #(= "tools/call" (:method %)) @log))

;; ── the map is the policy ───────────────────────────────────────────

(deftest the-map-carries-the-beads-email-rows-exactly
  (is (= {"emila__inbox" "email.read"
          "emila__list_messages" "email.read"
          "emila__search" "email.read"
          "emila__read" "email.read"
          "emila__read_batch" "email.read"
          "emila__download_attachment" "email.read"
          "emila__summary" "email.read"
          "emila__folders" "email.read"
          "emila__move" "email.move"
          "emila__move_from_sender" "email.move"
          "emila__send" "email.send"}
         gate/tool-capability)
      "this map IS the security policy — a changed row is a changed
       law, and this test is the diff a reviewer reads"))

;; ── acceptance 1: the affordance document is the grant's shadow ─────

(deftest the-affordance-document-shows-only-what-the-grant-admits
  (let [log (atom [])
        eng (boot log)]
    (mint-capabilities! eng)

    (testing "email.read alone: the read links, and neither mutation form"
      (let [worn (wear! eng [{:kind "email.read" :actions []}])
            r (call! eng :get "/api/-/gate" :headers worn)
            doc (:doc r)]
        (is (= 200 (:status r)) (pr-str doc))
        (is (= #{:emila__inbox :emila__search} (set (keys (:links doc))))
            "Gate's live reads ∩ the grant — and gsd__agenda, live at
             Gate but absent from the map, does not exist here")
        (is (= {} (:actions doc))
            "no email.send and no email.move means no forms at all")
        (is (not (str/includes? (str (:body r)) "emila__send"))
            "the send tool's NAME is not in the document either")
        (testing "…and a read affordance is Gate's own schema, why-surfaced"
          (let [inbox (get-in doc [:links :emila__inbox])]
            (is (= "/api/-/gate/emila__inbox" (:href inbox)))
            (is (= "POST" (:method inbox)))
            (is (= "email.read" (:capability inbox)))
            (is (contains? (get-in inbox [:input :properties]) :folder)
                "the input schema is Gate's own")
            (is (contains? (get-in inbox [:input :properties]) :why))
            (is (not (contains? (get-in inbox [:input :properties]) :__why))
                "Gate's __why convention crosses this surface as `why`")))))

    (testing "email.read + email.send: the send form appears, move stays out"
      (let [worn (wear! eng [{:kind "email.read" :actions []}
                             {:kind "email.send" :actions []}])
            doc (:doc (call! eng :get "/api/-/gate" :headers worn))
            send (get-in doc [:actions :emila__send])]
        (is (= #{:emila__inbox :emila__search} (set (keys (:links doc)))))
        (is (= #{:emila__send} (set (keys (:actions doc))))
            "email.move is not worn, so emila__move is not offered")
        (is (= "email.send" (:capability send)))
        (is (true? (get-in send [:why :required]))
            "a mutation says out loud that a human will read the why")
        (is (= ["to" "subject" "why"] (get-in send [:input :required]))
            "…and the form's required list speaks `why`, not `__why`")))

    (testing "a grant admitting nothing reads an empty document — and
              Gate is never contacted for it"
      (let [before (count @log)
            bare (:doc (call! eng :get "/api/-/gate" :headers as-claude))
            human (:doc (call! eng :get "/api/-/gate" :headers as-mom))]
        (is (= {} (:links bare)))
        (is (= {} (:actions bare)))
        (is (some? (:ask bare))
            "empty is not silent: the way to ask rides the document")
        (is (= {} (:links human)))
        (is (= {} (:actions human))
            "an unscoped human is not excused either — a capability is
             worn, never inherited from being trusted")
        (is (= before (count @log))
            "no admitted token, no wire: the fake heard nothing")))

    (testing "a FILTERED grant is not admitted — this door interprets
              no constraint yet, and half-honouring one would be worse"
      (let [worn (wear! eng [{:kind "email.read" :actions []
                              :filter {:folder "Receipts"}}])
            doc (:doc (call! eng :get "/api/-/gate" :headers worn))]
        (is (= {} (:links doc)))
        (is (= {} (:actions doc)))))

    (testing "the anonymous get the feed door's own 404"
      (is (= 404 (:status (call! eng :get "/api/-/gate")))))))

;; ── acceptance 2: an ungranted invoke never reaches Gate ────────────

(deftest an-ungranted-invoke-is-refused-in-process
  (let [log (atom [])
        eng (boot log)]
    (mint-capabilities! eng)
    (let [read-only (wear! eng [{:kind "email.read" :actions []}])]

      (testing "email.read does not admit emila__send — 403, the ask
                named, and the fake's call log stays empty"
        (let [r (call! eng :post "/api/-/gate/emila__send"
                       :headers read-only
                       :body {:to "x@y.z" :subject "hi" :body "…"
                              :why "because"})]
          (is (= 403 (:status r)) (pr-str (:doc r)))
          (is (str/includes? (str (get-in r [:doc :detail])) "email.send")
              "capabilities are words: the refusal says what to ask for")
          (is (seq (get-in r [:doc :remedies])))
          (is (= [] (gate-calls log)) "nothing was forwarded")))

      (testing "no grant at all is the same in-process refusal"
        (let [r (call! eng :post "/api/-/gate/emila__inbox"
                       :headers as-claude :body {})]
          (is (= 403 (:status r)))
          (is (= [] (gate-calls log)))))

      (testing "a tool outside the map does not exist — 404, even
                though the fake Gate serves gsd__agenda live"
        (let [r (call! eng :post "/api/-/gate/gsd__agenda"
                       :headers read-only :body {})]
          (is (= 404 (:status r)))
          (is (= [] (gate-calls log)))))

      (testing "a filtered grant refuses the invoke too, saying why"
        (let [worn (wear! eng [{:kind "email.move" :actions []
                                :filter {:folder "Receipts"}}])
              r (call! eng :post "/api/-/gate/emila__move"
                       :headers worn
                       :body {:uids [1] :destination "Receipts"
                              :why "filing"})]
          (is (= 403 (:status r)))
          (is (str/includes? (str (get-in r [:doc :detail])) "filter")
              (pr-str (:doc r)))
          (is (= [] (gate-calls log)))))

      (testing "the anonymous 404 before any judgment at all"
        (is (= 404 (:status (call! eng :post "/api/-/gate/emila__inbox"
                                   :body {}))))
        (is (= [] (gate-calls log)))))))

;; ── acceptance 3: a granted call forwards, verbatim, storing nothing ─

(deftest a-granted-read-and-a-granted-send-reach-the-forward-path
  (let [log (atom [])
        eng (boot log)]
    (mint-capabilities! eng)
    (let [worn (wear! eng [{:kind "email.read" :actions []}
                           {:kind "email.send" :actions []}])
          before (row-census eng)]

      (testing "a granted read forwards and answers Gate's payload"
        (let [r (call! eng :post "/api/-/gate/emila__inbox"
                       :headers worn :body {:folder "INBOX"})]
          (is (= 200 (:status r)) (pr-str (:doc r)))
          (is (= "gate answered emila__inbox"
                 (get-in r [:doc :content 0 :text]))
              "Gate's CallToolResult, verbatim — content, isError, all")
          (is (false? (get-in r [:doc :isError])))
          (let [call (last (gate-calls log))]
            (is (= "emila__inbox" (get-in call [:params :name])))
            (is (= {:folder "INBOX"} (get-in call [:params :arguments]))))))

      (testing "a granted send forwards with `why` translated to
                Gate's own `__why`"
        (let [r (call! eng :post "/api/-/gate/emila__send"
                       :headers worn
                       :body {:to "x@y.z" :subject "hi" :body "…"
                              :why "the household asked"})]
          (is (= 200 (:status r)) (pr-str (:doc r)))
          (is (= "gate answered emila__send"
                 (get-in r [:doc :content 0 :text])))
          (let [args (get-in (last (gate-calls log)) [:params :arguments])]
            (is (= "the household asked" (:__why args))
                "Gate's approver reads the sentence the caller wrote")
            (is (not (contains? args :why))
                "…and the translation left no second spelling behind")
            (is (= {:to "x@y.z" :subject "hi" :body "…"}
                   (dissoc args :__why))))))

      (testing "an empty body forwards as empty arguments, not a 500"
        (let [r (call! eng :post "/api/-/gate/emila__inbox" :headers worn)]
          (is (= 200 (:status r)))
          (is (= {} (get-in (last (gate-calls log)) [:params :arguments])))))

      (testing "and NOTHING was stored: no kind in this engine grew a
                row — the payload passed through, mirrored nowhere"
        (is (= before (row-census eng)))
        (is (not (contains? (inv/resources eng) :gate))
            "the proxy is not a resource kind and never becomes one")))))
