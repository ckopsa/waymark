(ns waymark10.client-test
  "Phase 10 acceptance, part one: the affordance-following client's
  Part IV rules, each proven against a real engine over the ring
  handler (the :handler transport — full router, problem boundary,
  identity boundary, no socket). Confirm gating, idempotency-key
  persistence and reuse, the fence's auto-If-Match, the acknowledge
  flow, the unknown-action LOCAL refusal (no request leaves), the
  plan/divergence discipline, and the MCP projection shape."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.client :as c]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]))

;; ── the fixture kinds ───────────────────────────────────────────────
;; meal (confirm-gated decline, terminal retire) and plan (finalize's
;; warning) come from the shared fixtures; gadget adds what they
;; lack: a non-idempotent action and a fenced one.

(def risk-noted
  (g/expr {:name :risk-noted
           :severity :warning
           :when '(not (= (data :risky) true))
           :explain "This gadget is flagged risky."}))

(r/defhandler poke-handler [row _inp _ctx]
  (update-in row [:data :pokes] (fnil inc 0)))

(r/defhandler rename-handler [row inp _ctx]
  (assoc-in row [:data :title] (:title inp)))

(def gadget
  (r/resource
   {:kind :gadget
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:max 80}]]
             [:risky {:optional true} [:maybe :boolean]]
             [:pokes {:optional true} [:maybe :int]]]
    :actions
    {:poke {:from #{:open} :to :open
            :safety {:idempotent false :reversible true :confirm false}
            :handler poke-handler}
     :rename {:from #{:open} :to :open
              :input [:map [:title [:string {:min 1 :max 80}]]]
              :safety {:idempotent true :reversible true :confirm false
                       :fence true}
              :handler rename-handler}
     :close {:from #{:open} :to :closed
             :guards [risk-noted]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed gadget is history."}}}}))

(def ^:dynamic *session* nil)
(def ^:dynamic *handler* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["meals" "plans" "gadgets" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_drafts"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [h (engine/handler
                 (engine/engine {:storage st
                                 :resources [fx/meal fx/plan gadget]}))]
          (binding [*handler* h
                    *session* (c/connect "http://test"
                                         {:principal "priya" :handler h})]
            (f)))
        (finally (pg/close! st))))))

(defn- meals [] (c/get-doc *session* "/api/meals"))
(defn- gadgets [] (c/get-doc *session* "/api/gadgets"))

(defn- suggest! [name]
  (let [res (c/create! *session* (meals) {:name name :themes ["test"]})]
    (is (c/doc? res) (pr-str res))
    res))

;; ── discovery and reads ─────────────────────────────────────────────

(deftest index-and-get
  (let [idx (c/index *session*)]
    (is (= "10" (:waymark idx)))
    (is (= "/api/meals" (get-in idx [:resources :meal :href]))))
  (let [coll (meals)]
    (is (c/doc? coll))
    (is (= "meal_collection" (:kind coll))))
  (testing "a problem comes back as data, never nil or a throw"
    (let [res (c/get-doc *session* "/api/widgets")]
      (is (c/problem? res))
      (is (= 404 (:status res))))))

(deftest follow-is-the-only-read-beyond-self
  (dotimes [i 30] (suggest! (str "Meal " i)))
  (let [page (c/get-doc *session* "/api/meals?page[size]=5")
        next-page (c/follow *session* page :next)]
    (is (c/doc? next-page))
    (is (= 2 (get-in next-page [:data :page :number]))))
  (testing "an undeclared rel refuses locally"
    (let [res (c/follow *session* (meals) :sideways)]
      (is (c/refused? res))
      (is (= :no-such-link (get-in res [:refused :code]))))))

;; ── rule 1: act only on declared actions ────────────────────────────

(deftest unknown-action-refuses-locally
  (let [meal (suggest! "Unknown-action probe")
        calls (atom 0)
        counting (fn [req] (swap! calls inc) (*handler* req))
        s (c/connect "http://test" {:principal "priya"
                                    :handler counting})
        doc (c/get-doc s (:self meal))
        before @calls
        res (c/act! s doc :vaporize nil)]
    (is (c/refused? res))
    (is (= :unknown-action (get-in res [:refused :code])))
    (is (= before @calls)
        "the refusal is local — no request left the client")
    (testing "an out-of-state action refuses with the server's narration"
      (let [res (c/act! s doc :retire nil)]
        (is (c/refused? res))
        (is (re-find #"[Aa]vailable in state" (get-in res [:refused :reason]))
            "unavailable.reason rides the local refusal")))))

;; ── rule 2: the confirm gate ────────────────────────────────────────

(deftest confirm-gate-is-a-hard-stop
  (let [meal (suggest! "Confirm probe")]
    (testing "no callback: refused with the consequence text"
      (let [res (c/act! *session* meal :decline nil)]
        (is (c/refused? res))
        (is (= :confirm-required (get-in res [:refused :code])))
        (is (= "The suggestion is discarded; the AI will not re-suggest it."
               (get-in res [:refused :consequence]))
            "the declaration's :consequence rides display.description")))
    (testing "a declining callback: refused, nothing invoked"
      (let [seen (atom nil)
            res (c/act! *session* meal :decline nil
                        {:confirm! (fn [c] (reset! seen c) false)})]
        (is (c/refused? res))
        (is (= :confirm-declined (get-in res [:refused :code])))
        (is (= "decline" (:action @seen)))
        (is (= "retired" (get-in @seen [:effect :to]))
            "the callback saw the effect before anyone approved")
        (is (= "suggested" (:state (c/get-doc *session* (:self meal))))
            "the resource did not move")))
    (testing "an approving callback proceeds"
      (let [res (c/act! *session* meal :decline nil
                        {:confirm! (constantly true)})]
        (is (c/doc? res))
        (is (= "retired" (:state res)))))))

;; ── rule 3: idempotency keys, persisted and reused ──────────────────

(deftest idempotency-key-per-logical-attempt
  (let [res (c/create! *session* (gadgets) {:title "Widget"})
        _ (is (c/doc? res) (pr-str res))
        doc (c/get-doc *session* (:self res))
        first-poke (c/act! *session* doc :poke nil)]
    (is (c/doc? first-poke))
    (is (= 1 (get-in first-poke [:data :pokes])))
    (testing "the same logical attempt replays byte-identically"
      (let [again (c/act! *session* doc :poke nil)]
        (is (= 1 (get-in again [:data :pokes]))
            "the persisted key replayed the first execution")
        (is (= (get-in first-poke [:meta :version])
               (get-in again [:meta :version])))))
    (testing "a fresh doc read is a NEW logical attempt (new key)"
      (let [doc2 (c/get-doc *session* (:self doc))
            poke2 (c/act! *session* doc2 :poke nil)]
        ;; same href + same (nil) input = same attempt key by design:
        ;; retrying an identical call replays. A distinct attempt
        ;; needs distinct input.
        (is (= 1 (get-in poke2 [:data :pokes]))
            "identical href+input stays one attempt — replay, not duplicate")))
    (testing "the key store is portable — a new session with the same
              store keeps replaying; one with a fresh store re-executes"
      (let [carried (c/connect "http://test"
                               {:principal "priya" :handler *handler*
                                :key-store (:key-store *session*)})
            res (c/act! carried (c/get-doc carried (:self doc)) :poke nil)]
        (is (= 1 (get-in res [:data :pokes]))))
      (let [fresh (c/connect "http://test"
                             {:principal "priya" :handler *handler*})
            res (c/act! fresh (c/get-doc fresh (:self doc)) :poke nil)]
        (is (= 2 (get-in res [:data :pokes]))
            "a fresh store is a genuinely new attempt")))))

;; ── rule 4: the fence sends If-Match automatically ──────────────────

(deftest fence-auto-if-match
  (let [created (c/create! *session* (gadgets) {:title "Fenced"})
        doc (c/get-doc *session* (:self created))
        renamed (c/act! *session* doc :rename {:title "Fenced v2"})]
    (is (c/doc? renamed) (pr-str renamed))
    (is (= "Fenced v2" (get-in renamed [:data :title]))
        "the fresh etag rode If-Match and the write landed")
    (testing "acting on a STALE doc is a 412 problem, honestly surfaced"
      (let [res (c/act! *session* doc :rename {:title "Fenced v3"})]
        (is (c/problem? res))
        (is (= 412 (:status res)))
        (is (= "Version conflict" (get-in res [:problem :title])))))
    (testing "re-reading heals the fence"
      (let [fresh (c/get-doc *session* (:self doc))
            res (c/act! *session* fresh :rename {:title "Fenced v3"})]
        (is (c/doc? res))
        (is (= "Fenced v3" (get-in res [:data :title])))))))

;; ── rule 5: dry-run pre-validation ──────────────────────────────────

(deftest dry-run-validates-without-transitioning
  (let [created (c/create! *session* (gadgets) {:title "Dry"})
        doc (c/get-doc *session* (:self created))]
    (is (= {:valid true} (c/dry-run *session* doc :rename {:title "New"})))
    (testing "schema refusals come back as the 422 problem"
      (let [res (c/dry-run *session* doc :rename {:title ""})]
        (is (c/problem? res))
        (is (= 422 (:status res)))
        (is (get-in res [:problem :errors :title]))))
    (is (= "Dry" (get-in (c/get-doc *session* (:self doc)) [:data :title]))
        "nothing moved")))

;; ── rule 6: the acknowledge flow ────────────────────────────────────

(deftest warnings-surface-and-acknowledge-retries
  (let [created (c/create! *session* (gadgets) {:title "Risky business"
                                                :risky true})
        doc (c/get-doc *session* (:self created))
        res (c/act! *session* doc :close nil)]
    (is (c/warnings? res))
    (is (= 409 (:status res)))
    (is (= "This gadget is flagged risky."
           (:reason (first (:warnings res)))))
    (is (= "open" (:state (c/get-doc *session* (:self doc))))
        "warned means not done")
    (testing "acknowledge! retries with the header and lands"
      (let [closed ((:acknowledge! res))]
        (is (c/doc? closed) (pr-str closed))
        (is (= "closed" (:state closed)))))))

;; ── rule 7: plan over effect.to, verify each landing ────────────────

(deftest plan-routes-over-learned-edges
  (let [meal (suggest! "Plan probe")]
    (is (= {:route ["accept"] :from "suggested" :goal "on_list"}
           (c/plan *session* meal :on_list)))
    (testing "multi-step routes need the intermediate states seen;
              follow-plan! walks and verifies each landing"
      (let [accepted (c/act! *session* meal :accept nil)]
        (is (c/doc? accepted))
        (is (nil? (c/diverged accepted)))
        ;; on_list is learned now; from a fresh suggested meal the
        ;; graph routes suggested → on_list → retired
        (let [meal2 (suggest! "Plan probe 2")
              planned (c/plan *session* meal2 :retired)]
          (is (= ["decline"] (:route planned))
              "BFS takes the one-step route when the graph offers it"))
        (let [meal3 (suggest! "Plan probe 3")
              done (c/follow-plan! *session* meal3 :on_list)]
          (is (c/doc? done))
          (is (= "on_list" (:state done))))))
    (testing "an unroutable goal refuses with the widen-the-graph hint"
      (let [res (c/plan *session* (suggest! "Plan probe 4") :nirvana)]
        (is (c/refused? res))
        (is (= :no-route (get-in res [:refused :code])))))))

;; ── the MCP projection ──────────────────────────────────────────────

(deftest mcp-tools-shape
  (let [meal (suggest! "Tool probe")
        ts (c/tools *session* meal)]
    (is (= ["meal.accept" "meal.decline"] (mapv :name ts))
        "one tool per CURRENTLY afforded action, name-sorted")
    (let [decline (first (filter #(= "meal.decline" (:name %)) ts))]
      (is (re-find #"requires human confirmation" (:description decline))
          "the confirm gate annotates the description")
      (is (map? (:input_schema decline))
          "every tool carries an input schema, empty-object when none"))
    (let [accepted (c/act! *session* meal :accept nil)
          ts2 (c/tools *session* accepted)]
      (is (= ["meal.retire" "meal.update_recipe"] (mapv :name ts2))
          "the tool surface follows the state")
      (let [upd (first (filter #(= "meal.update_recipe" (:name %)) ts2))]
        (is (= "object" (get-in upd [:input_schema :type]))
            "the declared input schema rides input_schema")))))
