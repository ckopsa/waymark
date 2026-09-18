(ns waymark10.mcp-served-test
  "The MCP door counts the bytes it serves (docs/spec-seat.md R-10.6a,
  bead waymark-fp62.7.12).

  The bill is the sum, over turns, of everything the model read before
  that turn, and the larger part of that is the transcript — the tool
  answers this door serves. The sitting counted transitions and
  refusals and nothing else, so no record said WHICH tool served the
  bytes, and the decision of which tool to make smaller was a guess.
  `served` is that record: tool name to the calls and the bytes.

  What this suite proves, one deftest for each of the bead's five
  acceptance cases:

  - a `waymark_query` answer of N bytes under a bound session adds
    one call and N bytes under `waymark_query`, and a second answer
    adds to both;
  - a 404 and a 409 count their bytes too, because the model reads a
    refusal exactly as it reads an allowance;
  - a call from a session with no open sitting counts nothing;
  - the close freezes `served`, and a later call under the same grant
    does not move the closed row;
  - the ledger answers `served` by tool over the window, and
  `bytes_per_transition`, in the top-level answers and per model.

  NOT proved here, and recorded: the bytes of `waymark_power`. Gate's
  answer passes through the door word for word, so the door sees its
  size and counts it like any other tool's — but standing up a Gate
  for one number would be a second fake provider in this file.

  Memory storage, a locally-minted RSA keypair as the IdP's signing
  key, the real handler: no database, no network. The shape is
  mcp_refusals_test's, whose helpers this file re-spells because they
  are private there by design."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.routes.seats :as seat-routes]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.nio.charset StandardCharsets)
           (java.security KeyPairGenerator)
           (java.time Instant)))

;; ── the connector door, as mcp_refusals_test stands it up ───────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "served-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "served-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "served-key"}}))

(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})

(defn- bearer [claims] {"authorization" (str "Bearer " (mint claims))})

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))

(def ^:private clock
  "The engine's fixed now, and the ledger's window is measured from
  it — a wall clock would put the sitting outside the window on a slow
  runner."
  (Instant/parse "2026-09-17T09:00:00Z"))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [fx/meal]
                  :now-fn (fn [] clock)
                  :oidc {:issuer issuer :audience audience :jwks jwks
                         :app-url "https://app.test/"
                         :delegate-clients {"connector" "Claude"}}}))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- tool [h headers tool-name args]
  (let [resp (rpc h headers "tools/call" {:name tool-name :arguments args})]
    (assoc (get-in (json resp) [:result]) :status (:status resp))))

(defn- text-of [result] (str (get-in result [:content 0 :text])))
(defn- doc-of [result] (wire/read-json (text-of result)))

(defn- initialize! [h]
  (get-in (rpc h (bearer colton) "initialize"
               {:protocolVersion mcp/protocol-version
                :capabilities {} :clientInfo {:name "served" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(defn- with-session [sid] (assoc (bearer colton) "mcp-session-id" sid))

;; ── the office ──────────────────────────────────────────────────────

(def ^:private a-key
  "128 bits of base64url — what a machine mints and no hand types."
  "bWNwLXNlcnZlZC1jb3VudGVkLWtleS0wMDAx")

(defn- add-model! [eng]
  (:row (inv/create! eng :model
                     {:name "served-test-model" :display "Served 1"
                      :vendor "anthropic" :tier "strong"
                      :price_input_per_mtok 3M
                      :price_output_per_mtok 15M
                      :price_cache_read_per_mtok 0.3M
                      :price_cache_write_per_mtok 3.75M}
                     {:principal person})))

(defn- open-seat!
  "A seat with its key offered. No schedule is minted: this suite is
  about what a bound session READS, not about a firing."
  [eng model]
  (let [seat (:row (inv/create! eng :seat
                                {:name "served-one"
                                 :charter "Decide whether a meal belongs on the list."
                                 :scope [{:kind "meal" :actions ["accept" "decline"]}]
                                 :held_for [(:id model)]
                                 :standing_ttl_seconds 604800
                                 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 ;; room enough that no wall closes in
                                 ;; mid-suite: this file is about the
                                 ;; byte counter, and a budget halt
                                 ;; would answer a refusal where an
                                 ;; allowance is being counted
                                 :sitting_budget_tokens 1000000}
                                {:principal person}))]
    (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                 {:principal person})
    seat))

(defn- meal!
  "One fresh row for the sitter to read and to act on."
  [eng name']
  (:row (inv/create! eng :meal {:name name' :themes []}
                     {:principal person})))

(defn- row-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- served-of
  "The sitting's whole `served` map, as stored — {} on a sitting that
  has read nothing, because the birth writes the empty map rather
  than a hole."
  [eng sitting-id]
  (or (:served (:data (row-of eng :sitting sitting-id))) {}))

(defn- line-of
  "One tool's line, as numbers rather than nils."
  [eng sitting-id tool-name]
  (let [line (get (served-of eng sitting-id) (keyword tool-name))]
    {:calls (long (or (:calls line) 0))
     :bytes (long (or (:bytes line) 0))}))

(defn- bytes-of
  "The UTF-8 length of the text this result answered — computed here
  from the very bytes the client read, so the assertion never repeats
  the door's own arithmetic on the door's own data."
  [result]
  (reduce (fn [n part]
            (+ (long n)
               (alength (.getBytes ^String (str (:text part))
                                   StandardCharsets/UTF_8))))
          0
          (:content result)))

(defn- sit!
  "A bound session, and the sitting it opened."
  [h]
  (let [sid (initialize! h)
        answer (doc-of (tool h (with-session sid) "waymark_sit" {:key a-key}))]
    {:sid sid
     :grant (str (:grant answer))
     :sitting (str (:sitting answer))}))

(def ^:private close-counts
  "One wake's usage, as a Stop hook sums it off the transcript. The
  numbers are the harness's; `served` is the engine's, and the close
  must not touch it."
  {:input_tokens 100000 :output_tokens 10000
   :cache_read_tokens 0 :cache_write_tokens 0 :turns 3})

(defn- close! [h sid sitting-id]
  (tool h (with-session sid) "waymark_invoke"
        {:kind "sitting" :id (str sitting-id) :action "close"
         :input close-counts}))

;; ── 1 · a tool answer counts under its own tool name ───────────────

(deftest a-query-answer-adds-its-calls-and-its-bytes-under-that-tool
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        _ (open-seat! eng model)
        {:keys [sid sitting grant]} (sit! h)
        _ (meal! eng "Soup")]

    (testing "the sit opened the sitting the counter looks for"
      (is (string? sitting))
      (is (= sitting (str (:id (seats/open-sitting-for-grant eng grant))))))

    (testing "and it has read nothing yet: the empty map, not a hole"
      (is (= {} (served-of eng sitting))
          "the birth writes `served` as {} beside the two zeroed counters"))

    (let [r1 (tool h (with-session sid) "waymark_query" {:kind "meal"})
          n1 (bytes-of r1)]
      (testing "the first query lands, and its bytes land under waymark_query"
        (is (false? (:isError r1)) (text-of r1))
        (is (pos? n1) "an answer with a row in it is not empty")
        (is (= {:calls 1 :bytes n1} (line-of eng sitting "waymark_query"))))

      (testing "a second answer adds to both counts"
        (let [r2 (tool h (with-session sid) "waymark_query" {:kind "meal"})
              n2 (bytes-of r2)]
          (is (false? (:isError r2)) (text-of r2))
          (is (= {:calls 2 :bytes (+ n1 n2)}
                 (line-of eng sitting "waymark_query")))))

      (testing "and no other tool was billed for it"
        (is (= [:waymark_query] (keys (served-of eng sitting)))
            "one key, and it is the tool that answered")))))

;; ── 2 · a refusal's bytes count too ────────────────────────────────

(deftest a-404-and-a-409-count-their-bytes-because-the-model-reads-them
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        _ (open-seat! eng model)
        {:keys [sid sitting]} (sit! h)
        dinner (:id (meal! eng "Stew"))]

    (testing "a 404 counts its bytes, both blocks of it"
      (let [r (tool h (with-session sid) "waymark_get"
                    {:kind "meal" :id "meal-nobody-minted"})
            n (bytes-of r)]
        (is (true? (:isError r)))
        (is (= 404 (:status (doc-of r))))
        (is (= 2 (count (:content r)))
            "the concealed-door hint rides every not-found, and it is read")
        (is (= {:calls 1 :bytes n} (line-of eng sitting "waymark_get")))))

    (let [ok (tool h (with-session sid) "waymark_invoke"
                   {:kind "meal" :id (str dinner) :action "accept"})
          n-ok (bytes-of ok)]
      (testing "the allowance counts, as every answer does"
        (is (false? (:isError ok)) (text-of ok))
        (is (= {:calls 1 :bytes n-ok} (line-of eng sitting "waymark_invoke"))))

      (testing "and the 409 that follows counts its own sentence"
        ;; `decline` leaves `suggested` only, so on an accepted meal it
        ;; is the engine's own wrong-state refusal — a second `accept`
        ;; would be a natural replay, answered 200
        (let [bad (tool h (with-session sid) "waymark_invoke"
                        {:kind "meal" :id (str dinner) :action "decline"})
              n-bad (bytes-of bad)]
          (is (true? (:isError bad)))
          (is (= 409 (:status (doc-of bad))))
          (is (pos? n-bad))
          (is (= {:calls 2 :bytes (+ n-ok n-bad)}
                 (line-of eng sitting "waymark_invoke"))
              "R-10.6a: an allowance and a refusal alike"))))

    (testing "the refusals counter and the byte counter stay apart"
      (let [d (:data (row-of eng :sitting sitting))]
        (is (= 1 (long (or (:transitions d) 0))))
        (is (= 1 (long (or (:refusals d) 0))))))))

;; ── 3 · no open sitting, nothing counted ───────────────────────────

(deftest a-session-with-no-open-sitting-counts-nothing
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        _ (open-seat! eng model)
        {:keys [sitting]} (sit! h)
        _ (meal! eng "Chowder")
        ;; a second session of the same person's tool: it never
        ;; presented the key, so it wears the delegate's leash and no
        ;; sitting stands under it
        loose (initialize! h)]

    (testing "the bound session's sitting is open and has read nothing"
      (is (= {} (served-of eng sitting))))

    (testing "the unbound session's call writes nothing anywhere"
      (let [r (tool h (with-session loose) "waymark_query" {:kind "meal"})]
        (is (some? (:content r)) "the door answered it, one way or the other")
        (is (pos? (bytes-of r)) "and the answer had bytes to count"))
      (is (= {} (served-of eng sitting))
          "R-10.6a: one lookup by grant, so another session's reading is
           never billed to this seat's wake"))

    (testing "and it opened no sitting of its own"
      (is (= 1 (count (store/with-tx (:storage eng)
                        (fn [tx]
                          (store/query-rows (:storage eng) tx :sitting
                                            {:state :open} {:limit 10})))))
          "the one the key opened, and no other"))))

;; ── 4 · the close freezes served ───────────────────────────────────

(deftest the-close-freezes-served-and-a-later-call-does-not-move-it
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        _ (open-seat! eng model)
        {:keys [sid sitting grant]} (sit! h)
        _ (meal! eng "Ramen")
        r1 (tool h (with-session sid) "waymark_query" {:kind "meal"})
        frozen {:calls 1 :bytes (bytes-of r1)}]

    (is (false? (:isError r1)) (text-of r1))
    (is (= frozen (line-of eng sitting "waymark_query")))

    (testing "the sitter closes its own wake"
      (let [r (close! h sid sitting)]
        (is (false? (:isError r)) (text-of r))
        (is (= "closed" (:state (doc-of r)))))
      (is (nil? (seats/open-sitting-for-grant eng grant))
          "and the grant stops resolving to it"))

    (testing "the close's own answer is not billed to the row it closed"
      (is (= frozen (line-of eng sitting "waymark_query")))
      (is (= {:calls 0 :bytes 0} (line-of eng sitting "waymark_invoke"))
          "by the time the counter runs the row is closed, so there is
           nothing open to write on"))

    (testing "a later call under the same grant writes nothing on it"
      (let [r (tool h (with-session sid) "waymark_query" {:kind "meal"})]
        (is (pos? (bytes-of r)) "the door still answers"))
      (is (= frozen (line-of eng sitting "waymark_query"))
          "R-10.6a: the close freezes `served`, as it freezes the two
           counters"))

    (testing "and the close wrote the harness's own numbers beside it"
      (let [d (:data (row-of eng :sitting sitting))]
        (is (= 100000 (:input_tokens d)))
        (is (= 3 (:turns d)))
        (is (= (:bytes frozen) (get-in d [:served :waymark_query :bytes]))
            "the tally leaves `served` as it is")))))

;; ── 5 · the ledger's seventh answer ────────────────────────────────

(deftest the-ledger-answers-served-by-tool-and-bytes-per-transition
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        seat (open-seat! eng model)
        {:keys [sid sitting]} (sit! h)
        soup (:id (meal! eng "Soup"))
        stew (:id (meal! eng "Stew"))
        accept (fn [id] (tool h (with-session sid) "waymark_invoke"
                              {:kind "meal" :id (str id) :action "accept"}))
        a1 (accept soup)
        a2 (accept stew)
        q1 (tool h (with-session sid) "waymark_query" {:kind "meal"})
        q2 (tool h (with-session sid) "waymark_query" {:kind "meal"})
        invoked (+ (bytes-of a1) (bytes-of a2))
        queried (+ (bytes-of q1) (bytes-of q2))
        total (+ invoked queried)]

    (testing "two allowances and two reads, all counted on the open wake"
      (is (false? (:isError a1)) (text-of a1))
      (is (false? (:isError a2)) (text-of a2))
      (is (= {:calls 2 :bytes invoked} (line-of eng sitting "waymark_invoke")))
      (is (= {:calls 2 :bytes queried} (line-of eng sitting "waymark_query"))))

    (close! h sid sitting)

    (let [doc (seat-routes/ledger eng
                                  (seat-routes/seat-row eng (:id seat))
                                  (.minusSeconds ^Instant clock 86400)
                                  clock)]
      (testing "the window holds the one closed wake, and its two acts"
        (is (= "seat_ledger" (:kind doc)))
        (is (= 2 (:transitions doc))))

      (testing "the seventh answer: which tool served the bytes"
        (is (= {:waymark_invoke {:calls 2 :bytes invoked}
                :waymark_query {:calls 2 :bytes queried}}
               (:served doc))
            "summed by tool over the window's closed sittings"))

      (testing "and the bytes each act cost"
        (is (= (long (Math/round (/ (double total) 2.0)))
               (:bytes_per_transition doc))
            "every byte served, over the two transitions"))

      (testing "by_model carries the same"
        (is (= 1 (count (:by_model doc))))
        (let [row (first (:by_model doc))]
          (is (= (str (:id model)) (:model row)))
          (is (= (:served doc) (:served row)))
          (is (= (:bytes_per_transition doc) (:bytes_per_transition row))))))

    (testing "a window with no closed sitting answers no bytes and no rate"
      (let [empty-doc (seat-routes/ledger eng
                                          (seat-routes/seat-row eng (:id seat))
                                          (.plusSeconds ^Instant clock 60)
                                          (.plusSeconds ^Instant clock 120))]
        (is (= {} (:served empty-doc)))
        (is (nil? (:bytes_per_transition empty-doc))
            "zero transitions have no bytes apiece, and 0 would be a lie
             in the cheap direction")))))
