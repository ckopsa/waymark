(ns waymark10.mcp-bulk-test
  "waymark_invoke over many rows (waymark-pywy.4): `ids` reaches the
  bulk door with one shared input, `items` with each row's own — the
  same fixed tools, none added for bulk. The confirm gate is per item: a confirm
  action refuses `ids` outright, and over `items` every row must
  carry its own consequence sentence or nothing runs. Everything
  else the model reads is the engine's own report, byte for byte —
  on_error, per-item dry-run verdicts, the guard's own sentence.

  Memory storage, the real handler through POST /api/-/mcp, a
  suite-local kind: no database, no network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.walks :as walks]
            [waymark10.wire :as wire]))

;; ── the suite-local kind ────────────────────────────────────────────

(def ^:private ready-gate
  (g/expr {:name :ready
           :when '(= (data :ready) true)
           :explain "This chore is not ready."}))

(r/defhandler tag-handler [row inp _ctx]
  (assoc-in row [:data :label] (:label inp)))

(def ^:private chore
  (r/resource
   {:kind :chore
    :states [:open :done :discarded]
    :initial :open
    :terminal #{:done :discarded}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:ready {:optional true} [:maybe :boolean]]
             [:label {:optional true} [:maybe [:string {:max 20}]]]]
    :actions
    {:complete {:from #{:open} :to :done
                :bulk {:max-items 10}
                :guards [ready-gate]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Done is done."}}
     :tag {:from #{:open} :to :open
           :bulk {:max-items 10}
           :input [:map [:label [:string {:min 1 :max 20}]]]
           :safety {:idempotent false :reversible true :confirm false}
           :handler tag-handler}
     ;; the one row door: a bulk action has no row form
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Done is done."}}
     :discard {:from #{:open} :to :discarded
               :bulk {:max-items 10}
               :safety {:idempotent true :reversible false :confirm true
                        :consequence "Discarded chores are gone for good."}}}}))

(def ^:private sentence "Discarded chores are gone for good.")

;; ── the door ────────────────────────────────────────────────────────

(defn- fresh []
  (engine/handler (engine/engine {:storage (memory/storage)
                                  :resources [chore]})))

(def ^:private headers {"x-waymark-principal" "colton"})

(defn- json [resp] (wire/read-json (:body resp)))

(defn- rpc [h method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json {:jsonrpc "2.0" :id 1 :method method
                              :params params})}))

(defn- invoke
  "One waymark_invoke → {:refused? :text :value} — the tool result's
  one text block, parsed where it parses."
  [h args]
  (let [resp (rpc h "tools/call" {:name "waymark_invoke" :arguments args})
        result (:result (json resp))
        text (get-in result [:content 0 :text])]
    (is (= 200 (:status resp)) (:body resp))
    {:refused? (boolean (:isError result))
     :text text
     :value (try (wire/read-json text) (catch Exception _ nil))}))

(defn- chore! [h data]
  (let [resp (h {:request-method :post :uri "/api/chores" :headers headers
                 :body (wire/write-json (merge {:title "chore" :ready true} data))})]
    (is (= 201 (:status resp)) (:body resp))
    (last (str/split (:self (json resp)) #"/"))))

(defn- row [h id]
  (json (h {:request-method :get :uri (str "/api/chores/" id) :headers headers})))

;; ── 1. the tool count did not grow ──────────────────────────────────

(deftest bulk-adds-no-tool
  (let [h (fresh)
        names (mapv :name (get-in (json (rpc h "tools/list" {})) [:result :tools]))]
    (is (= (count mcp/tools) (count names))
        "the fixed list as it stands (eight since spec-seat.md R-12.14) — bulk added nothing to it")
    (is (not-any? #(str/includes? % "bulk") names) "no seventh tool for bulk")
    (is (some #{"waymark_invoke"} names))
    (testing "and waymark_invoke's own schema names the two bulk arguments"
      (let [props (get-in (first (filter #(= "waymark_invoke" (:name %)) mcp/tools))
                          [:input-schema :properties])]
        (is (contains? props :ids))
        (is (contains? props :items))
        (is (contains? props :on_error))))))

;; ── 2. ids: one shared input, the engine's report ───────────────────

(deftest ids-reach-the-bulk-door
  (let [h (fresh)
        [a b c] [(chore! h {}) (chore! h {:ready false}) (chore! h {})]
        {:keys [refused? value]} (invoke h {:kind "chore" :action "complete" :ids [a b c]})]
    (is (false? refused?))
    (is (= "bulk_report" (:kind value)))
    (is (= {:succeeded 2 :refused 1 :failed 0}
           (select-keys (:data value) [:succeeded :refused :failed])))
    (testing "the refusal is the guard's own sentence, on the row's self"
      (let [[refusal] (get-in value [:data :refusals])]
        (is (= (str "/api/chores/" b) (:self refusal)))
        (is (= "This chore is not ready." (:reason refusal)))))
    (is (= ["done" "open" "done"] (mapv #(:state (row h %)) [a b c])))
    (testing "the shared input rides every row, and the door's key
              satisfies a non-idempotent action"
      (let [[d e] [(chore! h {}) (chore! h {})]
            {:keys [refused? value]} (invoke h {:kind "chore" :action "tag"
                                                :ids [d e] :input {:label "same"}})]
        (is (false? refused?))
        (is (= 2 (get-in value [:data :succeeded])))
        (is (= ["same" "same"] (mapv #(get-in (row h %) [:data :label]) [d e])))))))

;; ── 3. items: each row its own input ────────────────────────────────

(deftest items-carry-their-own-inputs
  (let [h (fresh)
        [a b] [(chore! h {}) (chore! h {})]
        {:keys [refused? value]} (invoke h {:kind "chore" :action "tag"
                                            :items [{:id a :input {:label "one"}}
                                                    {:id b :input {:label "two"}}]})]
    (is (false? refused?))
    (is (= 2 (get-in value [:data :succeeded])))
    (is (= ["one" "two"] (mapv #(get-in (row h %) [:data :label]) [a b])))
    (testing "on_error passes through: stop reports what did not run"
      (let [[c d e] [(chore! h {}) (chore! h {:ready false}) (chore! h {})]
            {:keys [refused? value]} (invoke h {:kind "chore" :action "complete"
                                                :items [{:id c} {:id d} {:id e}]
                                                :on_error "stop"})]
        (is (false? refused?))
        (is (= {:succeeded 1 :refused 1 :failed 0 :skipped 1}
               (select-keys (:data value) [:succeeded :refused :failed :skipped])))
        (is (= [{:self (str "/api/chores/" e)}] (get-in value [:data :not_run])))
        (is (= "open" (:state (row h e))))))
    (testing "and a dry run answers per-item verdicts with what would move"
      (let [[f g'] [(chore! h {}) (chore! h {:ready false})]
            {:keys [refused? value]} (invoke h {:kind "chore" :action "complete"
                                                :items [{:id f} {:id g'}]
                                                :dry_run true})]
        (is (false? refused?))
        (is (false? (:valid value)))
        (is (= ["ok" "refused"] (mapv :verdict (:verdicts value))))
        (is (= {:from "open" :to "done"} (:would (first (:verdicts value)))))
        (is (= "This chore is not ready." (:reason (second (:verdicts value)))))
        (is (= "open" (:state (row h f))) "a rehearsal writes nothing")))))

;; ── 4. the confirm gate is per item ─────────────────────────────────

(deftest a-confirm-action-refuses-ids
  (let [h (fresh)
        [a b] [(chore! h {}) (chore! h {})]
        {:keys [refused? text value]} (invoke h {:kind "chore" :action "discard"
                                                 :ids [a b] :acknowledge sentence})]
    (is refused?)
    (is (str/ends-with? (str (:type value)) "confirm-required"))
    (is (str/includes? text "per item"))
    (is (str/includes? text sentence) "the sentence owed is in the refusal")
    (is (= "items[].acknowledge" (get-in value [:acknowledge :argument])))
    (testing "nothing moved — a blanket acknowledgement over ids is
              refused even when it is the right sentence"
      (is (= ["open" "open"] (mapv #(:state (row h %)) [a b]))))))

(deftest items-acknowledge-each-for-themselves
  (let [h (fresh)
        [a b] [(chore! h {}) (chore! h {})]]
    (testing "one item without its sentence refuses the call, naming it"
      (let [{:keys [refused? text value]}
            (invoke h {:kind "chore" :action "discard"
                       :items [{:id a :acknowledge sentence} {:id b}]})]
        (is refused?)
        (is (str/ends-with? (str (:type value)) "confirm-required"))
        (is (= [{:id b :consequence sentence}] (:items value)))
        (is (str/includes? text "Nothing ran"))
        (is (= ["open" "open"] (mapv #(:state (row h %)) [a b]))
            "the acknowledged item did not run either — one call, one gate")))
    (testing "a paraphrase is no acknowledgement"
      (let [{:keys [refused? value]}
            (invoke h {:kind "chore" :action "discard"
                       :items [{:id a :acknowledge sentence}
                               {:id b :acknowledge "yes, discard it"}]})]
        (is refused?)
        (is (= [{:id b :consequence sentence :given "yes, discard it"}]
               (:items value)))))
    (testing "a call-level acknowledge never stands in for the items'"
      (let [{:keys [refused? value]}
            (invoke h {:kind "chore" :action "discard"
                       :acknowledge sentence
                       :items [{:id a} {:id b}]})]
        (is refused?)
        (is (= 2 (count (:items value))))
        (is (= ["open" "open"] (mapv #(:state (row h %)) [a b])))))
    (testing "every item carrying its own sentence runs"
      (let [{:keys [refused? value]}
            (invoke h {:kind "chore" :action "discard"
                       :items [{:id a :acknowledge sentence}
                               {:id b :acknowledge sentence}]})]
        (is (false? refused?))
        (is (= 2 (get-in value [:data :succeeded])))
        (is (= ["discarded" "discarded"] (mapv #(:state (row h %)) [a b])))))))

;; ── 5. one target, please ───────────────────────────────────────────

(deftest one-target-at-a-time
  (let [h (fresh)
        a (chore! h {})]
    (let [{:keys [refused? text]} (invoke h {:kind "chore" :action "complete"
                                             :id a :ids [a]})]
      (is refused?)
      (is (str/includes? text "One target")))
    (let [{:keys [refused? text]} (invoke h {:kind "chore" :action "complete"
                                             :ids [a] :items [{:id a}]})]
      (is refused?)
      (is (str/includes? text "not both")))
    (is (= "open" (:state (row h a))))
    (testing "an action the collection does not advertise as bulk is
              the engine's own 404, verbatim"
      (let [{:keys [refused? value]} (invoke h {:kind "chore" :action "zap" :ids [a]})]
        (is refused?)
        (is (= 404 (:status value)))))))

;; ── 6. a self walk holds what these doors wrote ─────────────────────

(defn- post [h hs uri body]
  (h {:request-method :post :uri uri :headers hs
      :body (wire/write-json body)}))

(defn- self-walk!
  "A recording walk of `pid`'s own, made at the HTTP door → its id."
  [h hs pid]
  (let [resp (post h hs "/api/walks" {:followed pid :title "My own walk"})]
    (is (= 201 (:status resp)) (:body resp))
    (last (str/split (:self (json resp)) #"/"))))

(defn- transitions-in
  "The `transition` frames a walk holds → their bodies."
  [eng walk-id]
  (let [st (:storage eng)
        frdef (get (inv/resources eng) :walk_frame)]
    (->> (store/with-tx st
           (fn [tx]
             (vec (store/query-rows st tx :walk_frame {:walk (str walk-id)}
                                    {:limit 100}))))
         (map #(:data (inv/decode-row frdef %)))
         (filter #(= "transition" (name (:type %))))
         (mapv #(walk/keywordize-keys (:body %))))))

(defn- moved [eng walk-id]
  (set (map (juxt :self :action) (transitions-in eng walk-id))))

(deftest a-bulk-invoke-lands-in-the-callers-self-walk
  (let [eng (engine/engine {:storage (memory/storage) :resources [chore]})
        h (engine/handler eng)
        [a b c d] [(chore! h {}) (chore! h {:ready false}) (chore! h {}) (chore! h {})]
        w (self-walk! h headers "colton")
        self #(str "/api/chores/" %)
        resp (post h headers "/api/chores/-/complete" {:ids [a b c]})]
    (is (= 200 (:status resp)) (:body resp))
    (is (= 2 (get-in (json resp) [:data :succeeded])))
    (testing "one transition frame per row that moved, none for the refused one"
      (is (= 2 (count (transitions-in eng w))))
      (is (= #{[(self a) "complete"] [(self c) "complete"]} (moved eng w))))
    (testing "a rehearsal moves nothing and records nothing"
      (let [dry (h {:request-method :post :uri "/api/chores/-/complete"
                    :query-string "dry_run=1" :headers headers
                    :body (wire/write-json {:ids [d]})})]
        (is (= 200 (:status dry)) (:body dry))
        (is (= "open" (:state (row h d))))
        (is (= 2 (count (transitions-in eng w))))))))

(deftest an-agents-invoke-lands-in-its-own-walk
  (let [eng (engine/engine {:storage (memory/storage) :resources [chore]})
        h (engine/handler eng)
        [a b c] [(chore! h {}) (chore! h {}) (chore! h {})]
        self #(str "/api/chores/" %)
        delegate {"x-waymark-principal" "agent-7" "x-waymark-actor-type" "agent"
                  "x-waymark-acts-for" "colton"}
        _ (inv/create! eng :grant
                       {:audience "agent-7"
                        :scope [{:kind "chore" :actions ["complete" "finish"]}
                                {:kind "walk" :actions ["create" "seal"]}]}
                       {:principal grants/approvals-actor
                        :id "grant-walk-1"
                        :mint? true})
        accepted (post h delegate "/api/grants/grant-walk-1/-/accept" {})
        agent (assoc delegate "x-waymark-grant" "grant-walk-1")
        w (self-walk! h agent "agent-7")
        call (fn [args]
               (let [resp (h {:request-method :post :uri "/api/-/mcp" :headers agent
                              :body (wire/write-json
                                     {:jsonrpc "2.0" :id 1 :method "tools/call"
                                      :params {:name "waymark_invoke"
                                               :arguments args}})})
                     result (:result (json resp))]
                 (is (= 200 (:status resp)) (:body resp))
                 (is (not (:isError result)) (get-in result [:content 0 :text]))))]
    (is (= 200 (:status accepted)) (:body accepted))
    (testing "one row, through waymark_invoke"
      (call {:kind "chore" :id a :action "finish"})
      (is (= #{[(self a) "finish"]} (moved eng w)))
      (is (= ["agent-7"]
             (mapv #(str (get-in % [:actor :id])) (transitions-in eng w)))))
    (testing "many rows, through the same tool's bulk door"
      (call {:kind "chore" :action "complete" :ids [b c]})
      (is (= 3 (count (transitions-in eng w))))
      (is (= #{[(self a) "finish"] [(self b) "complete"] [(self c) "complete"]}
             (moved eng w))))
    (testing "the export carries them under the agent's cast alias"
      (let [sealed (post h agent (str "/api/walks/" w "/-/seal") {})
            text (walks/export eng w nil)
            [header & lines] (mapv wire/read-json (str/split-lines text))]
        (is (= 200 (:status sealed)) (:body sealed))
        (is (= ["transition" "transition" "transition"] (mapv :type lines)))
        (is (= #{"a1"} (set (map :who lines))))
        (is (= [:a1] (keys (:cast header))))
        (is (= "agent" (get-in header [:cast :a1 :type])))))))
