(ns waymark10.judgment-walk-test
  "The seat that says a judgment, end to end (bead waymark-fp62.11,
  R-4, R-5 and R-7).

  THE QUEUE IS A QUERY. Nothing is copied and no row is minted for
  work not yet done: the sit reads the judgment's `subject_kind`
  under the judgment's `queue`, subtracts the subjects that already
  carry a standing verdict, and hands the seat what is left with the
  verdicts it may say. Judging one row takes it out of the next sit's
  queue — not by a flag on the subject, but because a verdict for it
  now exists.

  THE VERDICT IS THE PRODUCT. The seat's scope opens the subject kind
  READ-ONLY and kind verdict with the door `judge`, and nothing else
  — whether the subject moves at all is the judgment's `consequence`,
  walked afterwards by the log consumer (`server/judgments`) under
  the engine's own actor. The seat never sees that door and could not
  open it if it tried.

  The harness is mcp_sit_test's: memory storage, a locally-minted RSA
  keypair as the IdP's signing key, the real MCP handler. Its four
  helpers are re-spelled here rather than reached for — they are
  private there, which is the same trade that file made of
  connector_door_test's.

  `judgment` and `verdict` are the REAL framework kinds. Both enrol
  `:always` in the module table, so this suite declares only its own
  subject kind — the `expense` below, whose `nudge` is the harmless
  self-loop a consequence can be pointed at."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.judgments :as judgments]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

;; ── the connector door, as mcp_sit_test stands it up ────────────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "judgment-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "judgment-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "judgment-key"}}))

(defn- bearer [claims]
  {"authorization" (str "Bearer " (mint claims))})

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))
(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- rpc
  "One JSON-RPC message at /api/-/mcp through the real handler."
  [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- call!
  "One tools/call, answering the parsed tool result."
  [h headers tool-name args]
  (let [resp (rpc h headers "tools/call" {:name tool-name :arguments args})]
    (assoc (get-in (json resp) [:result]) :status (:status resp))))

(defn- text-of [result]
  (str (get-in result [:content 0 :text])))

(defn- doc-of [result]
  (wire/read-json (text-of result)))

(defn- initialize!
  "The handshake, answering the session id."
  [h]
  (get-in (rpc h (bearer colton) "initialize"
               {:protocolVersion mcp/protocol-version
                :capabilities {} :clientInfo {:name "routine" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(defn- with-session [sid] (assoc (bearer colton) "mcp-session-id" sid))

;; ── the subjects this judgment is about ─────────────────────────────

(def ^:private expense
  "The subject kind: a queue that filters itself, one field a
  judgment's `queue` can name (`team`), and ONE self-loop door for a
  consequence to be pointed at. `nudge` changes nothing a person
  would miss — it counts, which is exactly what a test needs to see
  and what a seat must not be able to do."
  (r/resource
   {:kind :expense
    :plural "expenses"
    :states [:filed :settled]
    :initial :filed
    :terminal #{:settled}
    ;; no door settles an expense here: the test needs a queue and one
    ;; self-loop, and a tomb nothing reaches is what the check asks to
    ;; be said out loud
    :allow-dead #{:settled}
    :summary "{data.vendor} · {state}"
    :schema
    [:map
     [:vendor {:x-display {:label "Who was paid"}} [:string {:min 1 :max 60}]]
     [:team {:x-display {:label "Whose budget"}} [:string {:min 1 :max 40}]]
     [:received_at {:x-display {:label "When it arrived"}} :waymark/instant]
     [:nudged {:optional true :x-display {:label "Times nudged"}}
      [:maybe [:int {:min 0 :max 1000}]]]]
    :filterable {:state #{:eq :in} :team #{:eq}}
    :default-filters {:state "filed"}
    :sortable {:fields [:received_at] :default "received_at"}
    :actions
    {:nudge {:from #{:filed} :to :filed
             :safety {:idempotent true :reversible true :confirm false}
             :handler (fn [row _inp _ctx]
                        (update-in row [:data :nudged] (fnil inc 0)))
             :display {:label "Nudge" :order 1
                       :description "Somebody is asked about it again"}}}}))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [expense]
                  :oidc {:issuer issuer :audience audience :jwks jwks
                         :app-url "https://app.test/"
                         :delegate-clients {"connector" "Claude"}}}))

;; ── the law, the office and the rows ────────────────────────────────

(def ^:private seat-key
  "128 bits of base64url — what a machine mints and no hand types."
  "c2VhdC1rZXktZm9yLXRoZS1qdWRnZS1zZWF0")

(def ^:private charter
  "Say one verdict on each expense, and one sentence of remedy with it.")

(def ^:private keep-sentence
  "The expense stands as filed and nobody is asked about it again.")

(def ^:private query-sentence
  "The expense is queried: somebody has to say what it was for.")

(defn- promoted-judgment!
  "A judgment over `expense`, filtered to one team, promoted — only a
  promoted judgment is walked."
  [eng extra]
  (let [row (:row (inv/create!
                   eng :judgment
                   (merge {:name "kitchen-spend"
                           :subject_kind "expense"
                           :queue {"team" "kitchen"}
                           :verdicts [{:name "keep" :sentence keep-sentence}
                                      {:name "query" :sentence query-sentence}]
                           :remedy_max 200
                           :notes "The first pass over the kitchen's card."}
                          extra)
                   {:principal person}))]
    (inv/invoke! eng :judgment (str (:id row)) :promote {} {:principal person})
    row))

(defn- open-judge-seat!
  "The office: it WALKS the judgment's subjects and says its verdicts.
  The scope opens `expense` with no actions at all — the seat reads
  the subjects and cannot move one — and `verdict` with the one door
  that makes a verdict, which is the seat's whole product."
  [eng judgment extra]
  (let [model (:row (inv/create! eng :model
                                 {:name "claude-judge-5" :display "Judge 5"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create!
                    eng :seat
                    (merge {:name "expense-judge"
                            :charter charter
                            :scope [{:kind "expense" :actions []}
                                    {:kind "verdict" :actions ["judge"]}]
                            :walk "expense"
                            :judgment (str (:id judgment))
                            :held_for [(:id model)]
                            :standing_ttl_seconds 604800
                            :cadence_seconds 3600
                            :budget_usd_per_week 5M
                            :sitting_budget_tokens 60000}
                           extra)
                    {:principal person}))]
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key seat-key}
                 {:principal person})
    seat))

(defn- expense!
  [eng vendor team at]
  (:row (inv/create! eng :expense {:vendor vendor :team team :received_at at}
                     {:principal person})))

(defn- sit!
  "One firing's sit, through the real door → [session-id result answer]."
  [h]
  (let [sid (initialize! h)
        r (call! h (with-session sid) "waymark_sit" {:key seat-key})]
    [sid r (doc-of r)]))

(defn- judge!
  "The seat's own product, said through `waymark_invoke` with no id —
  `judge` is this kind's create verb."
  [h sid input]
  (call! h (with-session sid) "waymark_invoke"
         {:kind "verdict" :action "judge" :input input}))

(defn- verdict-input [judgment subject-id extra]
  (merge {:judgment (str (:id judgment))
          :subject_kind "expense"
          :subject_id (str subject-id)
          :verdict "keep"
          :remedy "Nothing to do: the card is the kitchen's and the amount fits."}
         extra))

;; ── 1 · the walk is the judgment's queue, minus what it has judged ──

(deftest the-sit-walks-the-judgments-subjects-and-carries-its-verdicts
  (let [eng (fresh-engine)
        h (engine/handler eng)
        judgment (promoted-judgment! eng {})
        _ (open-judge-seat! eng judgment {})
        knives (expense! eng "Knife shop" "kitchen" "2026-09-18T07:00:00Z")
        flour (expense! eng "Flour mill" "kitchen" "2026-09-18T08:00:00Z")
        tyres (expense! eng "Tyre place" "garage" "2026-09-18T09:00:00Z")
        [sid r answer] (sit! h)
        walk (:walk answer)]

    (testing "the queue is the judgment's, not the kind's whole table"
      (is (false? (:isError r)) (text-of r))
      (is (= "expense" (:kind walk)))
      (is (= [(str (:id knives)) (str (:id flour))] (mapv :id (:rows walk)))
          "the judgment's queue filters by team, oldest first")
      (is (not (some #{(str (:id tyres))} (mapv :id (:rows walk))))
          "another team's expense is not this judgment's subject"))

    (testing "the law rides with the rows, so no turn is spent reading it"
      (let [j (:judgment walk)]
        (is (= (str (:id judgment)) (:id j)))
        (is (= "kitchen-spend" (:name j)))
        (is (= "expense" (:subject_kind j)))
        (is (= 200 (:remedy_max j)))
        (is (= [{:name "keep" :sentence keep-sentence}
                {:name "query" :sentence query-sentence}]
               (mapv #(select-keys % [:name :sentence]) (:verdicts j)))
            "every verdict it may say, with the sentence each one means")))

    (testing "and the subject rows carry NO doors: the verdict is the product"
      (is (every? (comp empty? :doors) (:rows walk))
          "the scope opens expense read-only, so the sitter is offered
           nothing to do to one"))

    (testing "the note names the door, the kind and every field it takes"
      (is (str/includes? (str (:note answer))
                         "invoke judge on kind verdict"))
      (is (str/includes? (str (:note answer)) "remedy_max characters")))

    (testing "a verdict said takes that subject out of the next sit"
      (let [said (judge! h sid (verdict-input judgment (:id knives) {}))]
        (is (false? (:isError said)) (text-of said))
        (let [[_ _ answer'] (sit! h)]
          (is (= [(str (:id flour))] (mapv :id (get-in answer' [:walk :rows])))
              "the queue is a query: the judged subject is gone because a
               verdict for it now exists, not because anything moved"))))

    (testing "and a second verdict on the same subject is refused"
      (let [again (judge! h sid (verdict-input judgment (:id knives)
                                               {:verdict "query"
                                                :remedy "Ask the kitchen."}))]
        (is (true? (:isError again))
            "one standing verdict per subject under one judgment — a
             correction is a `corrects`, not a second opinion")))))

;; ── 2 · the consequence is the mirror's, never the seat's ───────────

(deftest the-consequence-is-walked-on-the-subject-after-the-verdict
  (let [eng (fresh-engine)
        h (engine/handler eng)
        judgment (promoted-judgment! eng {:consequence "nudge"})
        _ (open-judge-seat! eng judgment {})
        knives (expense! eng "Knife shop" "kitchen" "2026-09-18T07:00:00Z")
        cursor :judgments-consequence-test
        ;; seed the cursor BEFORE the verdict, then drain it by hand:
        ;; wakes_test's discipline, and its reason — a consumer drained
        ;; from origin would replay a neighbouring deftest's log
        _ (consumers/drain-consumer! eng cursor (judgments/consumer-fn eng))
        [sid _ _] (sit! h)
        said (judge! h sid (verdict-input judgment (:id knives) {}))]

    (is (false? (:isError said)) (text-of said))

    (testing "before the drain the subject has not moved at all"
      (is (nil? (get-in (store/with-tx (:storage eng)
                          (fn [tx] (store/load-row (:storage eng) tx :expense
                                                   (str (:id knives)) {})))
                        [:data :nudged]))
          "the verdict is the product; the consequence is a consequence"))

    (testing "the drain walks the judgment's door on the subject"
      (consumers/drain-consumer! eng cursor (judgments/consumer-fn eng))
      (let [row (store/with-tx (:storage eng)
                  (fn [tx] (store/load-row (:storage eng) tx :expense
                                           (str (:id knives)) {})))]
        (is (= 1 (long (get-in row [:data :nudged]))))))

    (testing "under the engine's own actor, and not the sitter's"
      (let [ts (store/with-tx (:storage eng)
                 (fn [tx] (store/transitions
                           (:storage eng) tx
                           {:kind :expense :resource-id (str (:id knives))}
                           {})))
            nudge (first (filter #(= :nudge (:action %)) ts))]
        (is (some? nudge) "the door was walked, and the log says so")
        (is (= (:id schedules/system-actor) (str (get-in nudge [:actor :id])))
            "a seat could not have opened this door: its scope never
             named one on the subject")))

    (testing "and the replay is free — the key is the verdict's own"
      (consumers/drain-consumer! eng cursor (judgments/consumer-fn eng))
      (let [row (store/with-tx (:storage eng)
                  (fn [tx] (store/load-row (:storage eng) tx :expense
                                           (str (:id knives)) {})))]
        (is (= 1 (long (get-in row [:data :nudged])))
            "at-least-once delivery, exactly-once consequence")))))

;; ── 3 · a judgment with no consequence leaves the subject alone ─────

(deftest a-judgment-with-no-consequence-has-no-effect-on-the-subject
  (let [eng (fresh-engine)
        h (engine/handler eng)
        judgment (promoted-judgment! eng {})
        _ (open-judge-seat! eng judgment {})
        knives (expense! eng "Knife shop" "kitchen" "2026-09-18T07:00:00Z")
        cursor :judgments-no-consequence-test
        _ (consumers/drain-consumer! eng cursor (judgments/consumer-fn eng))
        [sid _ _] (sit! h)
        said (judge! h sid (verdict-input judgment (:id knives) {}))]
    (is (false? (:isError said)) (text-of said))
    (consumers/drain-consumer! eng cursor (judgments/consumer-fn eng))
    (let [row (store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx :expense
                                         (str (:id knives)) {})))
          ts (store/with-tx (:storage eng)
               (fn [tx] (store/transitions
                         (:storage eng) tx
                         {:kind :expense :resource-id (str (:id knives))}
                         {})))]
      (is (nil? (get-in row [:data :nudged])))
      (is (empty? (filter #(= :nudge (:action %)) ts))
          "R-5: a judgment that names no consequence is a judgment whose
           whole product is the verdict"))))
