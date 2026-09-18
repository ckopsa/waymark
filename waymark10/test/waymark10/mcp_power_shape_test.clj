(ns waymark10.mcp-power-shape-test
  "The shape a caller may ask a power for (bead waymark-fp62.7.16,
  acceptance 5).

  The measured problem: one `waymark_power` call answered 179,483
  bytes of mail for three messages, which was 80 percent of
  everything the model read in that sitting, and each turn after it
  read the same bytes again. The answer is not a smaller mailbox. It
  is a caller that can say what it needs: `text_only` for the words
  without the markup, and `max_chars` for the first part of them.

  What this file proves:

  - a 60 KB HTML answer, asked for as text with a cap of 4,000,
    comes back under 5 KB;
  - the sitting's `served` line for `waymark_power` says what the
    door dropped, so the record shows what the shape was worth;
  - the same call WITHOUT the two arguments passes the payload
    through byte for byte — this engine never rewrites an answer
    nobody asked it to.

  mcp_served_test records that it does not measure `waymark_power`,
  because standing up a Gate for one number would put a second fake
  provider in that file. This is that file: one fake Gate, one
  sitting, and the byte counter read from the row.

  Memory storage, no network, no OIDC. The session is built from the
  grant itself (`grants/visibility`), which is what the transport
  hands the tool layer."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant)))

;; ── the message, and the Gate that answers it ───────────────────────

(def ^:private mail-html
  "One HTML message of about 60 KB — a style block, a script block,
  and the two lines a person actually reads."
  (str "<html><head><style>body{font:13px sans-serif}</style>"
       "<script>pixel('open')</script></head><body>"
       "<p>Jen&nbsp;asks whether Thursday works &amp; wants the deck "
       "estimate confirmed before she orders the stain.</p>"
       (apply str
              (repeat 1000
                      "<div class=\"row\">The stain order waits on the answer.</div>"))
       "</body></html>"))

(defn- fake-gate
  "A caller shaped exactly as gate-proxy/http-rpc's answer: (fn
  [method params]) → the JSON-RPC :result. It answers the one message
  and writes down what it was asked, on the log the caller holds."
  [log]
  (fn [method params]
    (swap! log conj {:method method :params params})
    {:isError false
     :content [{:type "text" :text mail-html}]}))

(def ^:private clock (Instant/parse "2026-09-18T09:00:00Z"))

(def ^:private model-name "power-shape-model")

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private clerk
  (t/principal {:id "power-clerk" :type :agent :display "Clerk"
                :model model-name}))

(defn- fresh-engine [log]
  (engine/engine {:storage (memory/storage)
                  ;; the capability registry rides the app's own
                  ;; resources (:app-opt-in), exactly as workqueue10
                  ;; declares it — a dotted scope entry is judged
                  ;; against it
                  :resources [caps/capability]
                  :now-fn (fn [] clock)
                  ;; the tests' seam gate-proxy/rpc-of reads FIRST: no
                  ;; URL, no socket, no live Gate in this namespace
                  :gate {:rpc (fake-gate log)}}))

;; ── the office, the leash and the wake ──────────────────────────────

(defn- mint-capability! [eng]
  (:row (inv/create! eng :capability
                     {:token "email.read"
                      :description "Read email through Gate."
                      :enforced_by "this engine's own gate door"}
                     {:principal colton})))

(defn- wear-the-leash!
  "A grant naming the mail power, minted by a person and accepted by
  the sitter — the ordinary machinery, un-special-cased. → its id."
  [eng]
  (let [row (:row (inv/create! eng :grant
                               {:audience (:id clerk)
                                :scope [{:kind "email.read" :actions []}]}
                               {:principal colton}))]
    (inv/invoke! eng :grant (:id row) :accept {} {:principal clerk})
    (str (:id row))))

(defn- a-model! [eng]
  (:row (inv/create! eng :model
                     {:name model-name :display "Power Shape 1"
                      :vendor "anthropic" :tier "economy"
                      :price_input_per_mtok 1M
                      :price_output_per_mtok 5M
                      :price_cache_read_per_mtok 0.1M
                      :price_cache_write_per_mtok 1.25M}
                     {:principal colton})))

(defn- a-seat! [eng]
  (:row (inv/create! eng :seat
                     {:name "power-shape"
                      :charter "Decide whether a message asks something."
                      :scope [{:kind "capability" :actions []}]
                      :standing_ttl_seconds 604800
                      :cadence_seconds 3600
                      :budget_usd_per_week 5M
                      :sitting_budget_tokens 1000000}
                     {:principal colton})))

(defn- world
  "One engine with a sitter sitting: the capability registered, the
  leash worn, and one open sitting for the counter to write on.
  → {:eng :grant :sitting :session :calls}."
  []
  (let [log (atom [])
        eng (fresh-engine log)
        _ (mint-capability! eng)
        gid (wear-the-leash! eng)
        seat (a-seat! eng)
        model (a-model! eng)
        sitting (:row (inv/create! eng :sitting
                                   {:seat (:id seat) :model (:id model)
                                    :grant gid}
                                   {:principal clerk}))]
    {:eng eng
     :grant gid
     :calls log
     :sitting (:id sitting)
     :session {:principal clerk
               :visibility (grants/visibility eng gid clerk)}}))

(defn- power!
  "One `waymark_power` call through the whole message layer — the
  same path the transport drives, so the byte counter runs too."
  [{:keys [eng session]} args]
  (get-in (mcp/message eng (mcp/door eng) (get-in eng [:gate :rpc]) session
                       {:jsonrpc "2.0" :id 1 :method "tools/call"
                        :params {:name "waymark_power" :arguments args}})
          [:result]))

(defn- text-of [result]
  (str (get-in result [:content 0 :text])))

(defn- bytes-of
  "The UTF-8 length of the text this result answered, computed from
  the very bytes the client read."
  [result]
  (reduce (fn [n part]
            (+ (long n)
               (alength (.getBytes ^String (str (:text part))
                                   StandardCharsets/UTF_8))))
          0
          (:content result)))

(defn- served-line
  "The sitting's `served` line for one tool, as stored."
  [{:keys [eng sitting]} tool]
  (get-in (store/with-tx (:storage eng)
            (fn [tx] (store/load-row (:storage eng) tx :sitting
                                     (str sitting) {})))
          [:data :served (keyword tool)]))

;; ── the sitter's leash is real before anything else ─────────────────

(deftest the-sitter-wears-the-mail-power-and-has-an-open-sitting
  (let [w (world)]
    (is (some? (get-in w [:session :visibility :grant :id]))
        "a live grant, or the counter below has no sitting to find")
    (is (some? (grants/capability-entry (get-in w [:session :visibility])
                                        "email.read"))
        "and it names the mail power with no filter on it — what the
         power door reads before it touches any wire")))

;; ── acceptance 5 ────────────────────────────────────────────────────

(deftest text-only-with-a-cap-answers-under-five-kilobytes
  (let [w (world)
        result (power! w {:tool "emila__read"
                          :arguments {:message_id "m1"}
                          :text_only true
                          :max_chars 4000})
        answered (bytes-of result)
        calls @(:calls w)]

    (testing "the call reached Gate, and Gate answered 60 KB"
      (is (= 1 (count calls)))
      (is (= "emila__read" (get-in (first calls) [:params :name])))
      (is (< 50000 (alength (.getBytes ^String mail-html
                                       StandardCharsets/UTF_8)))))

    (testing "the model reads under 5 KB of it"
      (is (false? (:isError result)) (text-of result))
      (is (< answered 5120)
          "4,000 characters of words, where 60 KB of markup arrived")
      (is (re-find #"Jen asks whether Thursday works & wants" (text-of result))
          "the words the decision needs, with the entities decoded")
      (is (not (re-find #"<div|font:13px|pixel\(" (text-of result)))
          "and no tags, no style block and no script block"))

    (testing "the sitting says what the shape dropped"
      (let [line (served-line w "waymark_power")]
        (is (= 1 (:calls line)))
        (is (= answered (:bytes line))
            "what the model read, counted as every other tool's answer
             is")
        (is (pos? (long (:dropped line)))
            "…and beside it the bytes that never left this engine")
        (is (< 50000 (+ (long (:bytes line)) (long (:dropped line))))
            "served plus dropped is what the power itself answered,
             which is the one arithmetic that says what the shape was
             worth")))))

(deftest without-the-shape-the-payload-passes-through-byte-for-byte
  (let [w (world)
        result (power! w {:tool "emila__read"
                          :arguments {:message_id "m1"}})]
    (testing "Gate's own answer, unrewritten"
      (is (false? (:isError result)))
      (is (= mail-html (text-of result))
          "the engine never rewrites a payload unasked — the whole
           claim of the power door")
      (is (= (alength (.getBytes ^String mail-html StandardCharsets/UTF_8))
             (bytes-of result))))

    (testing "and the served line carries no dropped count at all"
      (let [line (served-line w "waymark_power")]
        (is (= 1 (:calls line)))
        (is (not (contains? line :dropped))
            "a tool nobody shaped keeps the two counts it always had")))))

(deftest a-cap-alone-cuts-the-payload-and-leaves-the-markup
  (let [w (world)
        result (power! w {:tool "emila__read"
                          :arguments {:message_id "m1"}
                          :max_chars 200})]
    (is (= (subs mail-html 0 200) (text-of result))
        "each argument acts alone: a cap with no text_only cuts the
         answer where the caller said and changes nothing else")
    (is (pos? (long (:dropped (served-line w "waymark_power")))))))

;; ── the shape is advertised where a model will read it ──────────────

(deftest the-tool-says-it-can-answer-smaller
  (let [tool (first (filter #(= "waymark_power" (:name %)) mcp/tools))
        props (get-in tool [:input-schema :properties])]
    (is (= "boolean" (get-in props [:text_only :type])))
    (is (= "integer" (get-in props [:max_chars :type])))
    (is (re-find #"text_only" (str (:description tool)))
        "a shape nobody is told about is a shape nobody asks for")
    (is (= #{:tool :arguments :text_only :max_chars} (set (keys props)))
        "and nothing else: the two arguments are the whole of the
         shape")
    (is (false? (get-in tool [:input-schema :additionalProperties]))
        "the door still refuses an argument it does not know")
    (is (string? (wire/write-json tool))
        "the listing is JSON, as every tool's is")))
