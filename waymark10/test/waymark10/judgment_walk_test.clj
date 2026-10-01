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
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.transcripts :as transcripts]
            [waymark10.server.wakes :as wakes]
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
                           :queue {:team "kitchen"}
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

;; ── 4 · a reopened verdict puts its subject back in the queue ───────
;;
;; The walk subtracts subjects with a SAID verdict and nothing else
;; (`mcp/judged-subjects`), so a reopen — which moves the standing
;; verdict to overruled and writes nothing in its place — hands the
;; subject back by that one rule. And because the reopen is an
;; ordinary transition, the seat that walks the judgment wakes on it
;; the way it wakes on a new subject: its computed default carries a
;; `verdict` `reopen` entry under its own judgment.

(def ^:private reopen-note
  "The bench bug that turned this red has been fixed; keep following it.")

(defn- reopen!
  "The judgment's owner takes a verdict back."
  [eng vid]
  (inv/invoke! eng :verdict (str vid) :reopen {:note reopen-note}
               {:principal person}))

(deftest a-reopened-subject-re-enters-the-walk
  (let [eng (fresh-engine)
        h (engine/handler eng)
        judgment (promoted-judgment! eng {})
        _ (open-judge-seat! eng judgment {})
        knives (expense! eng "Knife shop" "kitchen" "2026-09-18T07:00:00Z")
        flour (expense! eng "Flour mill" "kitchen" "2026-09-18T08:00:00Z")
        [sid _ _] (sit! h)
        said (judge! h sid (verdict-input judgment (:id knives) {}))
        ;; the default return is the envelope, whose address is `self`
        vid (last (str/split (str (:self (doc-of said))) #"/"))]
    (is (false? (:isError said)) (text-of said))
    (is (some? vid))
    (testing "judged, the subject is out of the queue"
      (let [[_ _ answer] (sit! h)]
        (is (= [(str (:id flour))] (mapv :id (get-in answer [:walk :rows]))))))
    (testing "reopened, it is back — and nothing was written in its place"
      (reopen! eng vid)
      (let [[_ _ answer] (sit! h)
            verdicts (store/with-tx (:storage eng)
                       (fn [tx] (store/query-rows (:storage eng) tx :verdict
                                                  {} {:limit 10})))]
        (is (= [(str (:id knives)) (str (:id flour))]
               (mapv :id (get-in answer [:walk :rows])))
            "oldest first, as before it was judged")
        (is (= 1 (count verdicts)))
        (is (= "overruled" (name (:state (first verdicts)))))))))

(deftest the-seat-that-walks-the-judgment-wakes-on-a-reopen
  (let [eng (fresh-engine)
        judgment (promoted-judgment! eng {})
        seat (open-judge-seat! eng judgment {})
        knives (expense! eng "Knife shop" "kitchen" "2026-09-18T07:00:00Z")
        ;; the verdict is said BEFORE the wake cursor is seeded, so the
        ;; only transition the drain hears is the reopen
        verdict (:row (inv/create! eng :verdict
                                   (verdict-input judgment (:id knives) {})
                                   {:principal (t/principal
                                                {:id "expense-sitter"
                                                 :type :agent})}))
        cursor :judgment-reopen-wakes-test
        _ (inv/invoke! eng :schedule
                       (str (:id (schedules/schedule-for-seat eng (:id seat))))
                       :link
                       {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                       "/routines/trig_expense_judge/fire")
                        :token "rk-test-expense-judge-0123456789abcdef"}
                       {:principal person})
        _ (consumers/drain-consumer! eng cursor (wakes/consumer-fn eng))
        seat-row (store/with-tx (:storage eng)
                   (fn [tx] (store/load-row (:storage eng) tx :seat
                                            (str (:id seat)) {})))]

    (testing "the computed default carries the reopen, under this judgment"
      (is (nil? (get-in seat-row [:data :wake_on])) "nothing is WRITTEN")
      (is (some #(and (= "verdict" (str (:kind %)))
                      (= ["reopen"] (mapv str (:actions %)))
                      (= (str (:id judgment))
                         (str (get-in % [:filter :judgment]))))
                (seats/effective-wake-on seat-row expense))))

    (testing "a reopen wakes the seat, and the text names what moved"
      (reopen! eng (:id verdict))
      (consumers/drain-consumer! eng cursor (wakes/consumer-fn eng))
      (let [fires (filterv #(= :fire (:action %))
                           (store/with-tx (:storage eng)
                             (fn [tx] (store/transitions
                                       (:storage eng) tx
                                       {:kind :seat :resource-id (str (:id seat))}
                                       {}))))
            text (str (get-in (first fires) [:inputs :text]))]
        (is (= 1 (count fires)))
        (is (str/includes? text (str (:id verdict))))
        (is (str/includes? text "reopen"))))

    (testing "a seat that WROTE its wake_on is woken by what it wrote"
      (let [written (assoc-in seat-row [:data :wake_on]
                              [{:kind "expense" :actions ["create"]}])]
        (is (= [{:kind "expense" :actions ["create"]}]
               (seats/effective-wake-on written expense))
            "it names verdict.reopen itself if it wants the reopen too")))))

;; ── 4b · a count over sealed transcripts leaves out the judged ───────

(def ^:private seal-count
  {:kind "transcript" :actions ["seal"] :at_least 5})

(def ^:private worker-sitter (t/principal {:id "worker-sitter" :type :agent}))

(defn- sitting-judgment!
  "A judgment over closed sittings, promoted: sitting-judge's shape."
  [eng]
  (let [row (:row (inv/create!
                   eng :judgment
                   {:name "fired-sittings"
                    :subject_kind "sitting"
                    :queue {:state "closed"}
                    :verdicts [{:name "keep" :sentence keep-sentence}
                               {:name "query" :sentence query-sentence}]
                    :remedy_max 200
                    :notes "The count wake's own judgment."}
                   {:principal person}))]
    (inv/invoke! eng :judgment (str (:id row)) :promote {} {:principal person})
    row))

(defn- raw-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- sealed-sitting!
  "One fired sitting of `worker`, closed, and its transcript sealed —
  the seal a count entry over `transcript` hears. → the sitting's id."
  [eng worker model grant]
  (let [sid (str (:id (:row (inv/create! eng :sitting
                                         {:seat (str worker)
                                          :model (str model)
                                          :grant (str grant)}
                                         {:principal worker-sitter}))))]
    (transcripts/issue-key! eng (raw-of eng :seat worker) (raw-of eng :sitting sid))
    (inv/invoke! eng :sitting sid :close
                 {:input_tokens 1000 :output_tokens 100
                  :cache_read_tokens 0 :cache_write_tokens 0 :turns 1}
                 {:principal worker-sitter})
    (transcripts/seal! eng (transcripts/transcript-for-sitting eng sid) nil)
    sid))

(defn- judge-sitting! [eng judgment sid]
  (inv/create! eng :verdict
               {:judgment (str (:id judgment))
                :subject_kind "sitting"
                :subject_id (str sid)
                :verdict "keep"
                :remedy "Nothing to do: the sitting did its work."}
               {:principal (t/principal {:id "expense-sitter" :type :agent})}))

(deftest a-judges-count-over-sealed-transcripts-counts-only-the-unjudged
  (let [eng (fresh-engine)
        judgment (sitting-judgment! eng)
        judge (open-judge-seat!
               eng judgment
               {:name "sitting-judge"
                :walk "sitting"
                :scope [{:kind "sitting" :actions []}
                        {:kind "transcript" :actions []}
                        {:kind "verdict" :actions ["judge"]}]
                :wake_on [seal-count]})
        model (first (get-in (raw-of eng :seat (:id judge)) [:data :held_for]))
        worker (:id (:row (inv/create!
                           eng :seat
                           {:name "worker"
                            :charter charter
                            :scope [{:kind "expense" :actions []}]
                            :walk "expense"
                            :held_for [(str model)]
                            :standing_ttl_seconds 604800
                            :cadence_seconds 3600
                            :budget_usd_per_week 5M
                            :sitting_budget_tokens 60000}
                           {:principal person})))
        grant (:id (:row (inv/create! eng :grant
                                      {:audience "worker"
                                       :scope [{:kind "expense" :actions []}]}
                                      {:principal person})))
        cursor :judge-count-wakes-test
        drain! #(consumers/drain-consumer! eng cursor (wakes/consumer-fn eng))
        fires #(count (filterv (fn [t] (= :fire (:action t)))
                               (store/with-tx (:storage eng)
                                 (fn [tx] (store/transitions
                                           (:storage eng) tx
                                           {:kind :seat :resource-id (str (:id judge))}
                                           {})))))
        n #(#'wakes/entry-count eng (raw-of eng :seat (:id judge)) seal-count)
        _ (inv/invoke! eng :schedule
                       (str (:id (schedules/schedule-for-seat eng (:id judge))))
                       :link
                       {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                       "/routines/trig_sitting_judge/fire")
                        :token "rk-test-sitting-judge-0123456789abcdef"}
                       {:principal person})
        _ (drain!)]

    (testing "four unjudged beside many judged wake nobody"
      (dotimes [_ 6]
        (judge-sitting! eng judgment (sealed-sitting! eng worker model grant))
        (drain!))
      (let [unjudged (vec (repeatedly 4 #(let [sid (sealed-sitting! eng worker model grant)]
                                           (drain!)
                                           sid)))]
        (is (= 4 (n)))
        (is (= 0 (fires)))

        (testing "the fifth unjudged seal wakes the seat once"
          (let [fifth (sealed-sitting! eng worker model grant)]
            (drain!)
            (is (= 5 (n)))
            (is (= 1 (fires)))

            (testing "judging one of the five drops the count to four"
              (judge-sitting! eng judgment (or (first unjudged) fifth))
              (is (= 4 (n))))))))

    (testing "a comma state in the entry's filter is any-of (ticket 708c0f8f)"
      (let [open-sid (str (:id (:row (inv/create! eng :sitting
                                                  {:seat (str worker)
                                                   :model (str model)
                                                   :grant (str grant)}
                                                  {:principal worker-sitter}))))
            _ (transcripts/issue-key! eng (raw-of eng :seat worker)
                                      (raw-of eng :sitting open-sid))
            either (assoc seal-count :filter {:state "open,sealed"})]
        (is (= :open (:state (transcripts/transcript-for-sitting eng open-sid)))
            "the unsealed sitting's transcript is open")
        (is (= 4 (n)) "the default `sealed` leaves the open one out")
        (is (= 5 (#'wakes/entry-count eng (raw-of eng :seat (:id judge)) either))
            "open,sealed counts the four unjudged sealed and the open one")))))

;; ── 5 · a listed verdict files one draft ticket ───────────────────────

(def ^:private groomers-ticket
  "The groomers' ticket, as much of factory10's as a filing needs."
  (r/resource
   {:kind :ticket
    :plural "tickets"
    :states [:draft :dropped]
    :initial :draft
    :terminal #{:dropped}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title {:x-display {:label "Title"}} [:string {:min 1 :max 200}]]
             [:detail {:optional true :x-display {:label "Detail"}}
              [:maybe [:string {:max 20000}]]]
             [:type {:x-display {:label "Type"}} [:string {:min 1 :max 20}]]
             [:priority {:x-display {:label "Priority"}} [:int {:min 0 :max 4}]]
             [:repo {:optional true :x-display {:label "Repo"}}
              [:maybe [:string {:max 140}]]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:drop {:from #{:draft} :to :dropped
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "Dropped is dropped."}
            :display {:label "Drop" :order 1
                      :description "Let this ticket go"}}}}))

(defn- ticket-engine []
  (engine/engine {:storage (memory/storage) :resources [expense groomers-ticket]}))

(def ^:private sitter (t/principal {:id "expense-sitter" :type :agent}))

(defn- say! [eng judgment subject word remedy]
  (:row (inv/create! eng :verdict
                     (verdict-input judgment (:id subject)
                                    {:verdict word :remedy remedy})
                     {:principal sitter})))

(defn- tickets [eng]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx :ticket {} {:limit 50}))))

(defn- drain-tickets! [eng]
  (consumers/drain-consumer! eng :judgment-ticket-test (judgments/consumer-fn eng)))

(def ^:private ask-remedy
  "Ask the kitchen what the knives were for.")

(deftest a-listed-verdict-files-one-draft-ticket
  (let [eng (ticket-engine)
        judgment (promoted-judgment! eng {:files_ticket_on ["query"]})
        knives (expense! eng "Knife shop" "kitchen" "2026-09-18T07:00:00Z")
        flour (expense! eng "Flour mill" "kitchen" "2026-09-18T08:00:00Z")
        _ (drain-tickets! eng)
        said (say! eng judgment knives "query" ask-remedy)]
    (testing "a listed verdict files one draft ticket naming the subject and the remedy"
      (drain-tickets! eng)
      (let [ts (tickets eng)
            tk (first ts)
            detail (str (get-in tk [:data :detail]))]
        (is (= 1 (count ts)))
        (is (= "draft" (name (:state tk))))
        (is (= "bug" (str (get-in tk [:data :type]))))
        (is (= 2 (long (get-in tk [:data :priority]))))
        (is (= "ckopsa/waymark" (str (get-in tk [:data :repo]))))
        (is (str/starts-with? (str (get-in tk [:data :title])) "query on expense "))
        (is (str/includes? detail ask-remedy))
        (is (str/includes? detail (str (:id knives))))
        (is (str/includes? detail (str (:id said))))))
    (testing "the replay files nothing more"
      (drain-tickets! eng)
      (is (= 1 (count (tickets eng)))))
    (testing "a verdict the judgment does not list files none"
      (say! eng judgment flour "keep" "Nothing to do.")
      (drain-tickets! eng)
      (is (= 1 (count (tickets eng)))))
    (testing "a second verdict on the same subject files none"
      (reopen! eng (:id said))
      (say! eng judgment knives "query" "Ask the kitchen once more.")
      (drain-tickets! eng)
      (is (= 1 (count (tickets eng)))))))

(deftest a-judgment-without-the-list-files-nothing
  (let [eng (ticket-engine)
        judgment (promoted-judgment! eng {})
        knives (expense! eng "Knife shop" "kitchen" "2026-09-18T07:00:00Z")]
    (drain-tickets! eng)
    (say! eng judgment knives "query" ask-remedy)
    (drain-tickets! eng)
    (is (empty? (tickets eng)))))

(deftest the-ticket-lands-in-the-judgments-repo
  (let [eng (ticket-engine)
        judgment (promoted-judgment! eng {:files_ticket_on ["query"]
                                          :ticket_repo "ckopsa/kitchen"})
        knives (expense! eng "Knife shop" "kitchen" "2026-09-18T07:00:00Z")]
    (drain-tickets! eng)
    (say! eng judgment knives "query" ask-remedy)
    (drain-tickets! eng)
    (is (= ["ckopsa/kitchen"] (mapv #(str (get-in % [:data :repo])) (tickets eng))))))

(deftest a-word-that-is-no-verdict-is-refused-at-create
  (let [eng (ticket-engine)
        refused (try
                  (inv/create! eng :judgment
                               {:name "kitchen-spend" :subject_kind "expense"
                                :verdicts [{:name "keep" :sentence keep-sentence}
                                           {:name "query" :sentence query-sentence}]
                                :files_ticket_on ["queried"]}
                               {:principal person})
                  nil
                  (catch Exception e e))]
    (is (some? refused) "the create is refused")
    (is (empty? (store/with-tx (:storage eng)
                  (fn [tx] (store/query-rows (:storage eng) tx :judgment
                                             {} {:limit 5})))))))

(deftest a-sittings-ticket-names-its-seat
  (is (= "cut_off on code-seat's sitting fc5faac6"
         (judgments/ticket-title "cut_off" "code-seat" "sitting"
                                 "fc5faac6-74c4-437d-a35c-fdbed565aa25")))
  (is (= "cut_off on expense 01234567"
         (judgments/ticket-title "cut_off" nil "expense" "0123456789"))))

;; ── 6 · a supersede takes the seats with it (ticket 86514746) ───────
;;
;; A judgment superseded with a successor re-points every seat that
;; says it, in the supersede's own transaction and through the seat's
;; own door, so the seat's log says it moved. A verdict is a row: it
;; stays cited to the judgment it was said under.

(defn- second-judge-seat!
  "A second office saying the same judgment — no key and no model of
  its own, because the first seat already minted both."
  [eng judgment first-seat]
  (:row (inv/create! eng :seat
                     {:name "expense-judge-too"
                      :charter charter
                      :scope [{:kind "expense" :actions []}
                              {:kind "verdict" :actions ["judge"]}]
                      :walk "expense"
                      :judgment (str (:id judgment))
                      :held_for (vec (get-in first-seat [:data :held_for]))
                      :standing_ttl_seconds 604800
                      :cadence_seconds 3600
                      :budget_usd_per_week 5M
                      :sitting_budget_tokens 60000}
                     {:principal person})))

(defn- raw-row [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- seat-log [eng seat]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind :seat :resource-id (str (:id seat))} {}))))

(defn- supersede! [eng judgment successor]
  (inv/invoke! eng :judgment (str (:id judgment)) :supersede
               {:successor (some-> successor :id str)} {:principal person}))

(deftest a-supersede-re-points-every-seat-that-says-the-judgment
  (let [eng (fresh-engine)
        old (promoted-judgment! eng {})
        successor (promoted-judgment! eng {:name "kitchen-spend-2"})
        one (open-judge-seat! eng old {})
        two (second-judge-seat! eng old one)
        knives (expense! eng "Knife shop" "kitchen" "2026-09-18T07:00:00Z")
        said (say! eng old knives "query" ask-remedy)]
    (supersede! eng old successor)
    (doseq [seat [one two]]
      (testing (str "seat " (get-in seat [:data :name]))
        (is (= (str (:id successor))
               (str (get-in (raw-row eng :seat (:id seat)) [:data :judgment])))
            "the seat says the successor now")
        (let [moves (filter #(#{:follow_successor :follow_successor_parked}
                               (:action %))
                            (seat-log eng seat))]
          (is (= 1 (count moves)) "the seat's own log carries the re-point")
          (is (str/includes? (pr-str moves) (str (:id old)))
              "and names the judgment it stood down from"))))
    (testing "a verdict said under the old judgment stays cited to it"
      (is (= (str (:id old))
             (str (get-in (raw-row eng :verdict (:id said)) [:data :judgment])))))))

(deftest a-seat-restated-to-a-superseded-judgment-is-refused-naming-the-successor
  (let [eng (fresh-engine)
        old (promoted-judgment! eng {})
        successor (promoted-judgment! eng {:name "kitchen-spend-2"})
        seat (open-judge-seat! eng old {})
        _ (supersede! eng old successor)
        raw (raw-row eng :seat (:id seat))
        refused (try
                  (inv/invoke! eng :seat (str (:id seat)) :restate
                               {:charter charter
                                :scope [{:kind "expense" :actions []}
                                        {:kind "verdict" :actions ["judge"]}]
                                :substitute_drop []
                                :held_for (vec (get-in raw [:data :held_for]))
                                :substitute_for []
                                :standing_ttl_seconds 604800
                                :cadence_seconds 3600
                                :budget_usd_per_week 5M
                                :sitting_budget_tokens 60000
                                :rows_per_firing 20
                                :walk "expense"
                                :judgment (str (:id old))}
                               {:principal person
                                :if-match (inv/etag :seat (str (:id seat))
                                                    (:version raw))})
                  nil
                  (catch Exception e e))]
    (is (some? refused) "the restate is refused")
    (is (str/includes? (pr-str (ex-data refused)) (str (:id successor)))
        "and the refusal names the successor")
    (is (= :walk-judgment-not-superseded (:guard (ex-data refused)))
        "the guard that judged it is the superseded one, not the walk's")
    (is (not-any? #(str/includes? (str %) "promote")
                  (:remedies (ex-data refused)))
        "and it offers no promote: a superseded judgment cannot be promoted")
    (is (= (str (:id successor))
           (str (get-in (raw-row eng :seat (:id seat)) [:data :judgment]))))))

(deftest a-seat-still-saying-a-superseded-judgment-is-refused-at-the-sit
  ;; a row written before the supersede re-pointed seats, or by a path
  ;; that skipped it: the seat is put back on the old judgment in the
  ;; store, by hand
  (let [eng (fresh-engine)
        h (engine/handler eng)
        old (promoted-judgment! eng {})
        successor (promoted-judgment! eng {:name "kitchen-spend-2"})
        seat (open-judge-seat! eng old {})
        _ (supersede! eng old successor)
        raw (raw-row eng :seat (:id seat))
        _ (store/with-tx (:storage eng)
            (fn [tx]
              (store/save-row! (:storage eng) tx :seat
                               (assoc-in raw [:data :judgment] (str (:id old)))
                               (:version raw))))
        ;; the refusal is a sentence, not a document: `sit!` would parse it
        r (call! h (with-session (initialize! h)) "waymark_sit" {:key seat-key})]
    (is (true? (:isError r)) (text-of r))
    (is (str/includes? (str (text-of r)) (str (:id successor)))
        "the refusal names the successor")))

(deftest a-superseded-judgment-sit-leaves-the-firings-key-unspent
  ;; ticket 017814ab: the refusal is judged before the spend, so the
  ;; same fire's key opens the sit once the seat is restated
  (let [eng (fresh-engine)
        h (engine/handler eng)
        old (promoted-judgment! eng {})
        successor (promoted-judgment! eng {:name "kitchen-spend-2"})
        seat (open-judge-seat! eng old {})
        _ (supersede! eng old successor)
        raw (raw-row eng :seat (:id seat))
        _ (store/with-tx (:storage eng)
            (fn [tx]
              (store/save-row! (:storage eng) tx :seat
                               (assoc-in raw [:data :judgment] (str (:id old)))
                               (:version raw))))
        ;; a seat with instructions is what a fire mints a key for
        fire-key (seats/hold-fire-key!
                  eng (assoc-in (raw-row eng :seat (:id seat))
                                [:data :instructions] "Judge the expenses.")
                  nil)
        sit-args {:key fire-key :seat "expense-judge"}
        refused (call! h (with-session (initialize! h)) "waymark_sit" sit-args)]
    (is (some? fire-key) "the fire minted a key")
    (is (true? (:isError refused)) (text-of refused))
    (is (str/includes? (str (text-of refused)) (str (:id successor)))
        "the refusal names the successor")
    (is (seats/fire-key-held? eng (raw-row eng :seat (:id seat)) fire-key)
        "the refused sit spent nothing")
    (let [raw (raw-row eng :seat (:id seat))]
      (inv/invoke! eng :seat (str (:id seat)) :restate
                   {:charter charter
                    :scope [{:kind "expense" :actions []}
                            {:kind "verdict" :actions ["judge"]}]
                    :substitute_drop []
                    :held_for (vec (get-in raw [:data :held_for]))
                    :substitute_for []
                    :standing_ttl_seconds 604800
                    :cadence_seconds 3600
                    :budget_usd_per_week 5M
                    :sitting_budget_tokens 60000
                    :rows_per_firing 20
                    :walk "expense"
                    :judgment (str (:id successor))}
                   {:principal person
                    :if-match (inv/etag :seat (str (:id seat)) (:version raw))}))
    (let [r (call! h (with-session (initialize! h)) "waymark_sit" sit-args)]
      (is (false? (:isError r)) (text-of r))
      (is (some? (:sitting (doc-of r))) "the same key opens the sit")
      (is (not (seats/fire-key-held? eng (raw-row eng :seat (:id seat)) fire-key))
          "and that sit is the one that spent it"))))

;; ── 9 · a judged subject is no row to wake for (ticket 871c8555) ─────

(deftest a-judged-subject-is-no-row-to-wake-for
  (let [eng (fresh-engine)
        judgment (promoted-judgment! eng {})
        seat (open-judge-seat! eng judgment {:max_open_sittings 3})
        knives (expense! eng "Knife shop" "kitchen" "2026-09-18T07:00:00Z")
        _ (inv/invoke! eng :schedule
                       (str (:id (schedules/schedule-for-seat eng (:id seat))))
                       :link
                       {:fire_url (str "https://api.anthropic.com/v1/claude_code"
                                       "/routines/trig_expense_judge/fire")
                        :token "rk-test-expense-judge-0123456789abcdef"}
                       {:principal person})
        seat-row #(raw-of eng :seat (:id seat))
        fires #(filterv (fn [t] (= :fire (:action t)))
                        (store/with-tx (:storage eng)
                          (fn [tx] (store/transitions
                                    (:storage eng) tx
                                    {:kind :seat :resource-id (str (:id seat))}
                                    {}))))]

    (testing "unjudged, the subject is a row the wake counts"
      (is (= 1 (#'wakes/walk-count eng (seat-row)))))

    (inv/create! eng :verdict (verdict-input judgment (:id knives) {})
                 {:principal (t/principal {:id "expense-sitter" :type :agent})})

    (testing "judged, the wake's walk is as empty as the sit's"
      (is (= 0 (#'wakes/walk-count eng (seat-row)))
          "the sit subtracts the judged subject, and so does the count")
      (is (true? (#'wakes/empty-walk? eng (seat-row)))))

    (testing "a closing sitting of a seat of several slots fires nothing"
      (is (nil? (wakes/release! eng (seat-row)
                                (schedules/schedule-for-seat eng (:id seat))
                                "wake:test:release:judged"
                                ((:now-fn eng))
                                true))
          "no slot is left a row to take, so the close is no wake")
      (is (empty? (fires))))))

;; ── 10 · an empty walk says when its filter emptied it (ticket 6ea8f277) ─

(def ^:private empty-queue-sentence
  "The queue held no rows under the walk's filter and the seat's grant.")

(defn- empty-sit!
  "One run's sit → [result answer why], `why` being the sentence the
  sit kept on its sitting."
  [eng h]
  (let [sid (initialize! h)
        r (call! h (with-session sid) "waymark_sit"
                 {:key seat-key :session "run-empty"})
        answer (doc-of r)]
    [r answer (get-in (raw-of eng :sitting (:sitting answer))
                      [:data :walked_nothing_why])]))

(deftest an-empty-walk-says-when-its-filter-left-rows-out
  (let [eng (fresh-engine)
        h (engine/handler eng)
        judgment (promoted-judgment! eng {})
        _ (open-judge-seat! eng judgment {})
        _ (expense! eng "Tyre place" "garage" "2026-09-18T07:00:00Z")
        _ (expense! eng "Oil depot" "garage" "2026-09-18T08:00:00Z")
        _ (expense! eng "Paint shop" "garage" "2026-09-18T09:00:00Z")
        [r answer why] (empty-sit! eng h)]
    (is (false? (:isError r)) (text-of r))
    (is (empty? (get-in answer [:walk :rows])))
    (is (= (str "Your walk filter (team=kitchen) leaves out 3 expense rows "
                "this seat can see.")
           why)
        "the seat sees three expenses, and its judgment's queue takes none")))

(deftest an-empty-walk-counts-no-row-the-grant-conceals
  ;; the scope entry's filter is the seat's sight: the garage's
  ;; expenses are outside it, so they are neither counted nor named
  (let [eng (fresh-engine)
        h (engine/handler eng)
        judgment (promoted-judgment! eng {})
        _ (open-judge-seat! eng judgment
                            {:scope [{:kind "expense" :actions []
                                      :filter {:team "kitchen"}}
                                     {:kind "verdict" :actions ["judge"]}]})
        _ (expense! eng "Tyre place" "garage" "2026-09-18T07:00:00Z")
        _ (expense! eng "Oil depot" "garage" "2026-09-18T08:00:00Z")
        [r answer why] (empty-sit! eng h)]
    (is (false? (:isError r)) (text-of r))
    (is (empty? (get-in answer [:walk :rows])))
    (is (= empty-queue-sentence why)
        "the plain sentence, with no count of what the grant cannot see")))

(deftest an-empty-queue-with-nothing-filtered-keeps-its-sentence
  (let [eng (fresh-engine)
        h (engine/handler eng)
        judgment (promoted-judgment! eng {})
        _ (open-judge-seat! eng judgment {})
        [r answer why] (empty-sit! eng h)]
    (is (false? (:isError r)) (text-of r))
    (is (empty? (get-in answer [:walk :rows])))
    (is (= empty-queue-sentence why))))
