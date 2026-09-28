(ns waymark10.transcript-test
  "The transcript of a sitting (docs/spec-transcript.md).

  A SITTING RECORDS WHAT A WAKE COST; ITS TRANSCRIPT RECORDS WHAT IT
  DID. The sit answers an address and a key beside the sitting's id,
  the hook appends the run's lines to that address, chained, and the
  sweep seals the record after the sitting ends and deletes its lines
  after the seat's days. Everything here is one of the spec's
  acceptance items (§ 12), numbered where it is one.

  Memory storage, a locally-minted RSA keypair as the IdP's signing
  key, the real handler: interactive_sitting_test's shape, whose
  helpers this file re-spells because they are private there. The
  clock is an atom the tests move."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.holds :as holds]
            [waymark10.server.definitions :as defs]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.transcripts :as transcripts]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.io ByteArrayOutputStream)
           (java.security KeyPairGenerator)
           (java.time Instant)
           (java.util.zip GZIPOutputStream)))

;; ── the connector door ──────────────────────────────────────────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "transcript-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "t-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "t-key"}}))

(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})

(defn- bearer [claims] {"authorization" (str "Bearer " (mint claims))})

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))

(defn- clock [] (atom (Instant/parse "2026-09-27T09:00:00Z")))

(defn- later! [at seconds] (swap! at #(.plusSeconds ^Instant % (long seconds))))

(defn- fresh-engine
  ([] (fresh-engine (clock)))
  ([at]
   (engine/engine {:storage (memory/storage)
                   :resources [fx/meal]
                   :now-fn (fn [] @at)
                   :oidc {:issuer issuer :audience audience :jwks jwks
                          :app-url "https://app.test/"
                          :delegate-clients {"connector" "Claude"}}})))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp"
      :headers (merge {"host" "work.test" "x-forwarded-proto" "https"} headers)
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
                :capabilities {} :clientInfo {:name "t" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(defn- with-session [sid] (assoc (bearer colton) "mcp-session-id" sid))

;; ── the office ──────────────────────────────────────────────────────

(def ^:private a-key
  "The seat's standing key: 128 bits of base64url."
  "dHJhbnNjcmlwdC1zZWF0LWtleS0wMQ")

(defn- add-model! [eng]
  (:row (inv/create! eng :model
                     {:name "t-model" :display "T 1"
                      :vendor "anthropic" :tier "strong"
                      :price_input_per_mtok 3M
                      :price_output_per_mtok 15M
                      :price_cache_read_per_mtok 0.3M
                      :price_cache_write_per_mtok 3.75M}
                     {:principal person})))

(defn- open-seat!
  ([eng model] (open-seat! eng model {}))
  ([eng model extra]
   (let [seat (:row (inv/create! eng :seat
                                 (merge {:name (str "scribe-" (or (:name extra) "one"))
                                         :charter "Decide whether a meal belongs on the list."
                                         :scope [{:kind "meal" :actions ["accept"]}]
                                         :held_for [(:id model)]
                                         :standing_ttl_seconds 604800
                                         :cadence_seconds 3600
                                         :budget_usd_per_week 5M
                                         :sitting_budget_tokens 60000}
                                        (dissoc extra :name))
                                 {:principal person}))]
     (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                  {:principal person})
     seat)))

(defn- row-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- rows-of [eng kind where]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit 1000}))))

(defn- sit!
  "Sit through the connector; → the sit's answer."
  [h]
  (doc-of (tool h (with-session (initialize! h)) "waymark_sit" {:key a-key})))

;; ── the lines a harness writes ──────────────────────────────────────

(defn- line [m] (wire/write-json m))

(def ^:private run-lines
  [(line {:type "user" :timestamp "2026-09-27T09:00:01Z"
          :message {:role "user" :content "Walk the meals."}})
   (line {:type "assistant" :timestamp "2026-09-27T09:00:02Z"
          :message {:role "assistant"
                    :content [{:type "tool_use" :id "tu-1"
                               :name "mcp__Waymark__waymark_query"
                               :input {:kind "meal"}}]
                    :usage {:input_tokens 1200 :output_tokens 40
                            :cache_read_input_tokens 9000
                            :cache_creation_input_tokens 300}}})
   (line {:type "user" :timestamp "2026-09-27T09:00:03Z"
          :message {:role "user"
                    :content [{:type "tool_result" :tool_use_id "tu-1"
                               :is_error true
                               :content [{:type "text" :text "404 Not found"}]}]}})
   (line {:type "assistant" :timestamp "2026-09-27T09:00:04Z"
          :message {:role "assistant"
                    :content [{:type "text" :text "The kind is absent. Stopping."}]}})])

(defn- chains
  "The hook's own arithmetic (R-5.3), from zeros."
  [lines]
  (vec (rest (reductions transcripts/chain-next transcripts/zero-chain lines))))

(defn- upload!
  ([h key body] (upload! h key body false))
  ([h key body gzip?]
   (let [text (wire/write-json body)]
     (h {:request-method :post :uri "/api/-/sittings/transcript"
         :headers (cond-> {"content-type" "application/json"}
                    key (assoc "waymark-transcript-key" key)
                    gzip? (assoc "content-encoding" "gzip"))
         :body (if gzip?
                 (let [out (ByteArrayOutputStream.)]
                   (with-open [z (GZIPOutputStream. out)]
                     (.write z (.getBytes text "UTF-8")))
                   (.toByteArray out))
                 text)}))))

(defn- body-of
  ([lines] (body-of "main" 0 transcripts/zero-chain lines))
  ([file from prior lines]
   {:file file :from from :prior prior :lines lines
    :harness_session "harness-1" :run_session "cse_run1"}))

(defn- close! [h]
  (h {:request-method :post :uri "/api/-/sittings/close"
      :headers {"waymark-seat-key" a-key}
      :body (wire/write-json {:input_tokens 1 :output_tokens 1
                              :cache_read_tokens 0 :cache_write_tokens 0
                              :turns 1})}))

;; ── the sit answers the key (R-4, R-3.7) ────────────────────────────

(deftest the-sit-answers-an-address-and-a-key-for-a-fired-sitting
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng (add-model! eng))
        sat (sit! h)
        tr (first (rows-of eng :transcript {:sitting (:sitting sat)}))]
    (is (= "https://work.test/api/-/sittings/transcript"
           (get-in sat [:transcript :url]))
        "absolute, from the origin the sit arrived under")
    (is (re-matches #"[A-Za-z0-9_-]{22}" (str (get-in sat [:transcript :key]))))
    (is (some? tr) "the sit gives birth to the transcript")
    (is (= :open (:state tr)))
    (is (= "fired" (get-in tr [:data :mode])))
    (is (not (str/includes? (wire/write-json (:data tr))
                            (str (get-in sat [:transcript :key]))))
        "the row keeps the hash, never the key")))

(deftest a-seat-that-keeps-none-answers-no-key
  (testing "12 · keep_transcripts none"
    (let [eng (fresh-engine)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng) {:keep_transcripts "none"})
          sat (sit! h)]
      (is (nil? (:transcript sat)))
      (is (empty? (rows-of eng :transcript {})))))
  (testing "13 · an interactive sitting at the default keeps none"
    (let [eng (fresh-engine)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng) {:mode "interactive"})
          sat (sit! h)]
      (is (= "interactive" (:mode sat)))
      (is (nil? (:transcript sat)))))
  (testing "…and keeps one when the seat says all"
    (let [eng (fresh-engine)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng) {:mode "interactive"
                                              :keep_transcripts "all"})
          sat (sit! h)]
      (is (string? (get-in sat [:transcript :key]))))))

;; ── the door (R-5) ──────────────────────────────────────────────────

(deftest lines-append-chained-and-each-line-is-a-row
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng (add-model! eng))
        sat (sit! h)
        key (get-in sat [:transcript :key])
        resp (upload! h key (body-of run-lines) true)
        doc (json resp)
        tr (first (rows-of eng :transcript {:sitting (:sitting sat)}))
        entries (sort-by #(get-in % [:data :n])
                         (rows-of eng :transcript_entry {:transcript (str (:id tr))}))]
    (testing "1 · the held count and the chain are the hook's own"
      (is (= 200 (:status resp)) (pr-str doc))
      (is (= 4 (:held doc)))
      (is (= (last (chains run-lines)) (:chain doc)))
      (is (= 4 (get-in tr [:data :lines])))
      (is (= "cse_run1" (get-in tr [:data :run_session])))
      (is (= "harness-1" (get-in tr [:data :harness_session]))))
    (testing "the engine derives what a line says"
      (is (= ["user" "assistant" "user" "assistant"]
             (map #(get-in % [:data :type]) entries)))
      (is (= "mcp__Waymark__waymark_query" (get-in (nth entries 1) [:data :tool])))
      (is (= "mcp__Waymark__waymark_query" (get-in (nth entries 2) [:data :tool]))
          "a tool_result is filed under the tool it answers")
      (is (true? (get-in (nth entries 2) [:data :is_error])))
      (is (= "404 Not found" (get-in (nth entries 2) [:data :text])))
      (is (= 1200 (get-in (nth entries 1) [:data :usage :input_tokens])))
      (is (= 300 (get-in (nth entries 1) [:data :usage :cache_write_tokens]))))
    (testing "2 · a second post of the same lines changes nothing"
      (let [again (upload! h key (body-of run-lines))]
        (is (= 200 (:status again)))
        (is (= 4 (:held (json again))))
        (is (= 4 (count (rows-of eng :transcript_entry {:transcript (str (:id tr))}))))))
    (testing "a post from the held line appends"
      (let [more [(line {:type "assistant" :message {:role "assistant" :content "Done."}})]
            r (upload! h key (body-of "main" 4 (last (chains run-lines)) more))]
        (is (= 200 (:status r)))
        (is (= 5 (:held (json r))))
        (is (= (last (chains (into run-lines more))) (:chain (json r))))))))

(deftest a-post-past-the-held-line-is-told-where-to-start
  (testing "3 · 409 with held, and the resend from held lands"
    (let [eng (fresh-engine)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng))
          key (get-in (sit! h) [:transcript :key])
          _ (upload! h key (body-of (subvec run-lines 0 2)))
          r (upload! h key (body-of "main" 3 (nth (chains run-lines) 2)
                                    [(nth run-lines 3)]))]
      (is (= 409 (:status r)))
      (is (= 2 (:held (json r))))
      (is (= 200 (:status (upload! h key (body-of "main" 2 (nth (chains run-lines) 1)
                                                  (subvec run-lines 2 4)))))))))

(deftest a-line-that-does-not-match-breaks-the-chain-and-says-so
  (testing "4 · the gap names the file and the line; nothing held moves"
    (let [eng (fresh-engine)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng))
          sat (sit! h)
          key (get-in sat [:transcript :key])
          _ (upload! h key (body-of run-lines))
          forged (assoc run-lines 3 (line {:type "assistant"
                                           :message {:role "assistant"
                                                     :content "All is well."}}))
          r (upload! h key (body-of forged))
          tr (first (rows-of eng :transcript {:sitting (:sitting sat)}))]
      (is (= 409 (:status r)))
      (is (= "File `main` diverged at line 3." (:detail (json r))))
      (is (= "File `main` diverged at line 3." (get-in tr [:data :gap])))
      (is (= 4 (get-in tr [:data :lines])))
      (is (= "The kind is absent. Stopping."
             (->> (rows-of eng :transcript_entry {:transcript (str (:id tr)) :seq 3})
                  first :data :text)))
      (testing "and the file takes no more lines"
        (is (= 409 (:status (upload! h key (body-of "main" 4 (last (chains run-lines))
                                                   [(line {:type "x"})]))))))
      (testing "another file still does"
        (is (= 200 (:status (upload! h key (body-of "agent-a1" 0 transcripts/zero-chain
                                                   [(line {:type "user"})])))))))))

(deftest one-sentence-for-every-key-that-does-not-answer
  (testing "5 · wrong, absent"
    (let [eng (fresh-engine)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng))
          _ (sit! h)]
      (doseq [k ["nope-nope-nope-nope-00" nil]]
        (let [r (upload! h k (body-of run-lines))]
          (is (= 404 (:status r)))
          (is (= transcripts/no-transcript (:detail (json r))))))))
  (testing "6 · a second sit mints a second key, and the first stops answering"
    (let [eng (fresh-engine)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng))
          first-key (get-in (sit! h) [:transcript :key])
          second-key (get-in (sit! h) [:transcript :key])]
      (is (not= first-key second-key))
      (is (= 404 (:status (upload! h first-key (body-of run-lines)))))
      (is (= 200 (:status (upload! h second-key (body-of run-lines))))))))

(deftest a-malformed-post-is-refused-before-a-row-is-read
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng (add-model! eng))
        key (get-in (sit! h) [:transcript :key])]
    (is (= 422 (:status (upload! h key (assoc (body-of run-lines) :file "../etc")))))
    (is (= 422 (:status (upload! h key (assoc (body-of run-lines) :prior "zz")))))
    (is (= 422 (:status (upload! h key (assoc (body-of run-lines) :extra 1)))))))

;; ── redaction (R-7.4) ───────────────────────────────────────────────

(deftest the-engine-redacts-what-the-hook-missed
  (testing "8 and 9 · a token pattern and a live standing key"
    (let [eng (fresh-engine)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng))
          sat (sit! h)
          key (get-in sat [:transcript :key])
          leaky (line {:type "assistant"
                       :message {:role "assistant"
                                 :content (str "key " a-key " and ghp_"
                                               "abcdefghijklmnopqrstuvwxyz0123")}})
          r (upload! h key (assoc (body-of [leaky]) :redactions {:env 2}))
          tr (first (rows-of eng :transcript {:sitting (:sitting sat)}))
          entry (first (rows-of eng :transcript_entry {:transcript (str (:id tr))}))]
      (is (= 200 (:status r)))
      (is (= (first (chains [leaky])) (:chain (json r)))
          "the chain is over what the hook sent, before the engine's pass")
      (is (not (str/includes? (get-in entry [:data :raw]) a-key)))
      (is (str/includes? (get-in entry [:data :raw]) "[redacted:seat-key]"))
      (is (str/includes? (get-in entry [:data :raw]) "[redacted:pattern]"))
      (is (= 2 (get-in tr [:data :engine_redactions])))
      (is (= 2 (get-in tr [:data :redactions :env])) "the hook's own counts are kept"))))

;; ── the transcript key closes its sitting (spec-seat.md R-12.17) ────

(defn- close-with! [h key]
  (h {:request-method :post :uri "/api/-/sittings/close"
      :headers {"waymark-transcript-key" key}
      :body (wire/write-json {:input_tokens 1200 :output_tokens 40
                              :cache_read_tokens 9000 :cache_write_tokens 300
                              :turns 2})}))

(deftest the-transcript-key-closes-its-own-sitting
  (let [at (clock)
        eng (fresh-engine at)
        h (engine/handler eng)
        seat (open-seat! eng (add-model! eng))
        sat (sit! h)
        key (get-in sat [:transcript :key])]
    (testing "the key names the sitting, and the door closes that one"
      (let [resp (close-with! h key)
            doc (json resp)
            sitting (row-of eng :sitting (:sitting sat))]
        (is (= 200 (:status resp)) (pr-str doc))
        (is (= (str (:sitting sat)) (:sitting doc)))
        (is (= (str (:id seat)) (:seat doc)))
        (is (= :closed (:state sitting)))
        (is (= 1200 (get-in sitting [:data :input_tokens])))
        (is (= 2 (get-in sitting [:data :turns])))))
    (testing "a second close is told the bill is in"
      (is (= 409 (:status (close-with! h key)))))
    (testing "a key that answers nothing is the seat key's sentence"
      (let [resp (close-with! h "bm9ib2R5LWhvbGRzLXRoaXM")]
        (is (= 404 (:status resp)))
        (is (= "No seat answers this key." (:detail (json resp))))))
    (testing "a key dropped at the seal answers the same"
      (later! at 900)
      (is (= 1 (:sealed (defs/sweep-seats! eng))))
      (let [resp (close-with! h key)]
        (is (= 404 (:status resp)))
        (is (= "No seat answers this key." (:detail (json resp))))))))

;; ── the seal and the purge (R-9) ────────────────────────────────────

(deftest the-sweep-seals-after-the-grace-and-drops-the-key
  (let [at (clock)
        eng (fresh-engine at)
        h (engine/handler eng)
        _ (open-seat! eng (add-model! eng))
        sat (sit! h)
        key (get-in sat [:transcript :key])
        _ (upload! h key (body-of run-lines))
        _ (later! at 60)
        _ (is (= 200 (:status (close! h))))]
    (testing "inside the grace a late Stop still lands"
      (later! at 120)
      (is (= 0 (:sealed (defs/sweep-seats! eng))))
      (is (= 200 (:status (upload! h key (body-of "main" 4 (last (chains run-lines))
                                                  [(line {:type "assistant"})]))))))
    (testing "7 · past the grace the sweep seals, and the key stops answering"
      (later! at 700)
      (is (= 1 (:sealed (defs/sweep-seats! eng))))
      (let [tr (first (rows-of eng :transcript {:sitting (:sitting sat)}))]
        (is (= :sealed (:state tr)))
        (is (nil? (get-in tr [:data :key_hash])))
        (is (some? (get-in tr [:data :sealed_at])))
        (is (nil? (get-in tr [:data :gap])) "the last post came after the end"))
      (is (= 404 (:status (upload! h key (body-of run-lines))))))))

(deftest the-clock-sweep-seals-without-a-boot
  (testing "the :seat-clock pass alone seals a transcript past its grace"
    (let [at (clock)
          eng (fresh-engine at)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng))
          sat (sit! h)
          key (get-in sat [:transcript :key])
          _ (upload! h key (body-of run-lines))
          _ (close! h)]
      (later! at 700)
      (is (= 1 (:sealed (defs/sweep-clock! eng))))
      (is (= :sealed (:state (first (rows-of eng :transcript
                                             {:sitting (:sitting sat)})))))
      (is (= 404 (:status (upload! h key (body-of run-lines))))))))

(deftest a-sitting-that-sent-nothing-says-so-when-sealed
  (testing "10 · No transcript was received."
    (let [at (clock)
          eng (fresh-engine at)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng))
          sat (sit! h)]
      (close! h)
      (later! at 700)
      (defs/sweep-seats! eng)
      (is (= "No transcript was received."
             (get-in (first (rows-of eng :transcript {:sitting (:sitting sat)}))
                     [:data :gap]))))))

(deftest the-sweep-deletes-the-lines-after-the-seats-days
  (testing "14 · the lines go; the counts and the chains stay"
    (let [at (clock)
          eng (fresh-engine at)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng) {:transcript_days 1})
          sat (sit! h)
          key (get-in sat [:transcript :key])
          _ (upload! h key (body-of run-lines))
          _ (close! h)
          _ (later! at 700)
          _ (defs/sweep-seats! eng)
          _ (later! at 86400)
          out (defs/sweep-seats! eng)
          tr (first (rows-of eng :transcript {:sitting (:sitting sat)}))]
      (is (= 1 (:purged out)))
      (is (= :purged (:state tr)))
      (is (empty? (rows-of eng :transcript_entry {:transcript (str (:id tr))})))
      (is (= 4 (get-in tr [:data :lines])))
      (is (= (last (chains run-lines)) (get-in tr [:data :files 0 :chain]))))))

(deftest a-person-deletes-the-lines-and-a-seat-cannot
  (let [at (clock)
        eng (fresh-engine at)
        h (engine/handler eng)
        _ (open-seat! eng (add-model! eng))
        sat (sit! h)
        _ (upload! h (get-in sat [:transcript :key]) (body-of run-lines))
        _ (close! h)
        _ (later! at 700)
        _ (defs/sweep-seats! eng)
        tr (first (rows-of eng :transcript {:sitting (:sitting sat)}))
        agent (t/principal {:id "seat:x" :type :agent :display "x (seat)"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (inv/invoke! eng :transcript (str (:id tr)) :purge {}
                              {:principal agent}))
        "no grant can hold the purge")
    (let [out (inv/invoke! eng :transcript (str (:id tr)) :purge {}
                           {:principal person})]
      (transcripts/after-purge! eng (get (inv/resources eng) :transcript) :purge out)
      (is (= :purged (:state (row-of eng :transcript (:id tr)))))
      (is (empty? (rows-of eng :transcript_entry {:transcript (str (:id tr))}))))))

(deftest a-seats-purge-is-held-for-its-person
  ;; The router's own steps, in its order: the refusal it turns into a
  ;; held call, the row it mints, the person's Allow, and the two
  ;; wire-boundary effects it chains after every committed invoke.
  (let [at (clock)
        eng (fresh-engine at)
        h (engine/handler eng)
        _ (open-seat! eng (add-model! eng))
        sat (sit! h)
        _ (upload! h (get-in sat [:transcript :key]) (body-of run-lines))
        _ (close! h)
        _ (later! at 700)
        _ (defs/sweep-seats! eng)
        tr (first (rows-of eng :transcript {:sitting (:sitting sat)}))
        tid (str (:id tr))
        agent (assoc (t/principal {:id "seat:x" :type :agent :display "x (seat)"})
                     :acts-for "colton")
        lines #(rows-of eng :transcript_entry {:transcript tid})
        held-rdef (get (inv/resources eng) :held_call)]
    (testing "a bare agent's purge is a hold, not a 409"
      (let [e (try (inv/invoke! eng :transcript tid :purge {} {:principal agent})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :not-an-agent (some-> (:guard (ex-data e)) name keyword))
            (pr-str (ex-data e)))
        (is (holds/hold? :not-an-agent)
            "the guard's `:hold true` registered it when the module loaded")
        (is (delegation/hold-guard? (:guard (ex-data e)))
            "so the router mints a held call in place of the refusal")
        (is (= "colton" (delegation/hold-owner {:principal agent}))
            "and the call waits on the person the seat acts for")))
    (let [held (held/hold-door! eng {:kind :transcript :action :purge :id tid
                                     :body {} :caller "seat:x" :owner "colton"
                                     :why "not-an-agent"})
          hid (str (:id held))]
      (testing "a forged :within, naming a call nobody allowed, is refused"
        (is (thrown? clojure.lang.ExceptionInfo
                     (inv/invoke! eng :transcript tid :purge {}
                                  {:principal agent
                                   :within {:kind :held_call :action :allow
                                            :id hid}})))
        (is (= :sealed (:state (row-of eng :transcript tid))))
        (is (seq (lines)) "no line was deleted"))
      (testing "the person's Allow runs the purge, and the lines go"
        (let [out (inv/invoke! eng :held_call hid :allow {} {:principal person})]
          (transcripts/after-purge! eng held-rdef :allow
                                    (held/after-allow! eng held-rdef :allow out)))
        (is (= :purged (:state (row-of eng :transcript tid))))
        (is (= :done (:state (row-of eng :held_call hid))))
        (is (empty? (lines)))
        (is (= 4 (get-in (row-of eng :transcript tid) [:data :lines]))
            "the counts stay")))))

;; ── the seat's two fields ───────────────────────────────────────────

(deftest the-seat-says-what-it-keeps-and-for-how-long
  (let [eng (fresh-engine)
        seat (open-seat! eng (add-model! eng))
        row (row-of eng :seat (:id seat))
        restate (get-in (inv/resources eng) [:seat :actions :restate])]
    (is (= "fired" (get-in row [:data :keep_transcripts])))
    (is (= 30 (get-in row [:data :transcript_days])))
    (is (contains? (set (get-in restate [:edit :prefill])) :keep_transcripts))
    (is (contains? (set (get-in restate [:edit :prefill])) :transcript_days))))

;; ── the seat's inbox, and the sit's key for it ──────────────────────

(def ^:private an-inbox {:only {:meal ["accept"]}})

(defn- restate-seat! [eng seat model extra]
  (let [row (row-of eng :seat (:id seat))]
    (inv/invoke! eng :seat (str (:id seat)) :restate
                 (merge {:charter "Decide whether a meal belongs on the list."
                         :scope [{:kind "meal" :actions ["accept"]}]
                         :held_for [(:id model)]
                         :standing_ttl_seconds 604800
                         :cadence_seconds 3600
                         :budget_usd_per_week 5M
                         :sitting_budget_tokens 60000}
                        extra)
                 {:principal person
                  :if-match (inv/etag :seat (str (:id seat)) (:version row))})))

(defn- refusal
  "The problem ex-data of a refused write, or nil when it was served."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(deftest a-seat-states-its-inbox-and-keeps-it
  (let [eng (fresh-engine)
        model (add-model! eng)
        seat (open-seat! eng model)]
    (is (nil? (get-in (row-of eng :seat (:id seat)) [:data :inbox]))
        "absent is no inbox")
    (restate-seat! eng seat model {:inbox an-inbox})
    (is (= an-inbox (get-in (row-of eng :seat (:id seat)) [:data :inbox])))
    (is (contains? (set (get-in (inv/resources eng)
                                [:seat :actions :restate :edit :prefill]))
                   :inbox))))

(deftest an-inbox-naming-what-is-not-served-is-refused
  (testing "a kind this engine does not serve, at create"
    (let [eng (fresh-engine)
          p (refusal #(open-seat! eng (add-model! eng)
                                  {:inbox {:only {:nosuchkind []}}}))]
      (is (= :inbox-names-real-kinds (:guard p)))
      (is (str/includes? (str (:detail p)) "nosuchkind"))))
  (testing "an action its kind does not have, at restate"
    (let [eng (fresh-engine)
          model (add-model! eng)
          seat (open-seat! eng model {:inbox an-inbox})
          p (refusal #(restate-seat! eng seat model
                                     {:inbox {:only {:meal ["explode"]}}}))]
      (is (= :inbox-names-real-actions (:guard p)))
      (is (str/includes? (str (:detail p)) "explode"))
      (is (= an-inbox (get-in (row-of eng :seat (:id seat)) [:data :inbox]))
          "the refused restate leaves the inbox as it was"))))

(deftest the-sit-answers-an-inbox-key-for-a-seat-with-an-inbox
  (testing "a seat with an inbox"
    (let [eng (fresh-engine)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng) {:inbox an-inbox})
          sat (sit! h)
          key (get-in sat [:inbox :key])]
      (is (= "https://work.test/api/-/sittings/inbox" (get-in sat [:inbox :url])))
      (is (re-matches #"[A-Za-z0-9_-]{22}" (str key)))
      (is (not (str/includes? (wire/write-json (:data (row-of eng :sitting (:sitting sat))))
                              (str key)))
          "the sitting keeps the hash, never the key")))
  (testing "a seat with none answers none"
    (let [eng (fresh-engine)
          h (engine/handler eng)
          _ (open-seat! eng (add-model! eng))
          sat (sit! h)]
      (is (nil? (:inbox sat))))))

(deftest the-inbox-key-finds-its-open-sitting-and-nothing-else
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng (add-model! eng) {:inbox an-inbox})
        first-sat (sit! h)
        first-key (get-in first-sat [:inbox :key])
        second-sat (sit! h)
        second-key (get-in second-sat [:inbox :key])]
    (testing "a re-sit mints a different key"
      (is (string? first-key))
      (is (not= first-key second-key)))
    (testing "the live key finds the sitting"
      (is (= (str (:sitting second-sat))
             (str (:id (seats/inbox-sitting-by-key eng second-key))))))
    (testing "an old key, a wrong key and no key find nothing"
      (is (nil? (seats/inbox-sitting-by-key eng first-key)))
      (is (nil? (seats/inbox-sitting-by-key eng "bm9ib2R5LWhvbGRzLXRoaXM")))
      (is (nil? (seats/inbox-sitting-by-key eng nil))))
    (testing "the key dies when the sitting ends"
      (is (= 200 (:status (close! h))))
      (is (nil? (seats/inbox-sitting-by-key eng second-key))))))

;; ── the inbox door (spec-seat.md R-12.38) ───────────────────────────

(def ^:private a-tail
  "Meals accepted, and every move of a model, which the seat's grant
  (meal only) cannot read."
  {:only {:meal ["accept"] :model []}})

(defn- tail!
  ([h key] (tail! h key {}))
  ([h key params]
   (h {:request-method :get :uri "/api/-/sittings/inbox"
       :query-string (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) params))
       :headers (cond-> {} key (assoc "waymark-inbox-key" key))})))

(defn- lines-of [resp]
  (mapv wire/read-json (remove str/blank? (str/split-lines (str (:body resp))))))

(defn- meal! [eng name']
  (:row (inv/create! eng :meal {:name name' :themes []} {:principal person})))

(defn- move! [eng meal action]
  (inv/invoke! eng :meal (str (:id meal)) action {} {:principal person}))

(defn- tailing! []
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng (add-model! eng) {:inbox a-tail})]
    [eng h (get-in (sit! h) [:inbox :key])]))

(deftest the-inbox-door-serves-what-the-inbox-names
  (let [[eng h key] (tailing!)
        soup (meal! eng "Soup")
        _ (move! eng soup :accept)
        _ (move! eng soup :retire)
        _ (inv/create! eng :model
                       {:name "t-model-2" :display "T 2"
                        :vendor "anthropic" :tier "strong"
                        :price_input_per_mtok 3M :price_output_per_mtok 15M
                        :price_cache_read_per_mtok 0.3M :price_cache_write_per_mtok 3.75M}
                       {:principal person})
        resp (tail! h key)
        lines (lines-of resp)]
    (testing "exactly the accepted meal: no create, no retire, no model"
      (is (= 200 (:status resp)))
      (is (= [{:kind "meal" :id (str (:id soup)) :action "accept"
               :from "suggested" :to "on_list"}]
             (mapv #(select-keys % [:kind :id :action :from :to]) lines)))
      (is (integer? (:event (first lines))))
      (is (string? (:at (first lines)))))
    (testing "`after` resumes past the events already read"
      (let [after (:event (first lines))]
        (is (empty? (lines-of (tail! h key {:after after}))))
        (let [stew (meal! eng "Stew")
              _ (move! eng stew :accept)
              more (lines-of (tail! h key {:after after}))]
          (is (= [(str (:id stew))] (mapv :id more))))))
    (testing "an unreadable `after` or `wait` is refused"
      (is (= 422 (:status (tail! h key {:after "soon"}))))
      (is (= 422 (:status (tail! h key {:wait 26})))))))

(deftest the-inbox-door-waits-for-an-event
  (let [[eng h key] (tailing!)
        soup (meal! eng "Soup")
        started (System/nanoTime)
        _ (future (Thread/sleep 300) (move! eng soup :accept))
        lines (lines-of (tail! h key {:wait 10}))]
    (testing "an event ends the wait early"
      (is (= ["accept"] (mapv :action lines)))
      (is (< (- (System/nanoTime) started) 8000000000)))
    (testing "the wait runs out empty"
      (let [started (System/nanoTime)
            resp (tail! h key {:after (:event (first lines)) :wait 1})]
        (is (= 200 (:status resp)))
        (is (empty? (lines-of resp)))
        (is (>= (- (System/nanoTime) started) 900000000))))))

(deftest the-inbox-door-refuses-a-key-it-cannot-place
  (let [[_ h key] (tailing!)]
    (is (= 401 (:status (tail! h nil))))
    (is (= 401 (:status (tail! h "bm9ib2R5LWhvbGRzLXRoaXM"))))
    (is (= 200 (:status (tail! h key))))
    (is (= 200 (:status (close! h))))
    (let [resp (tail! h key)]
      (is (= 401 (:status resp)))
      (is (str/includes? (str (:body resp)) "No open sitting answers this inbox key.")))))
