(ns waymark10.batch-b-access-test
  "Batch B acceptance — access completeness over the real ring
  handler: grant field/argument modes (the envelope, the published
  schema view, part items, and the summary honesty trap), the
  negotiation machine (approval_request: request → approve extends
  the grant / deny leaves the 404s standing), and the own-grant
  surface (a scoped principal reads its own grants and requests
  through the ordinary collection, narrowed by a visibility cond).
  Needs the batch database; export
  WAYMARK10_TEST_DSN=jdbc:postgresql://localhost:5433/waymark10_b_test?user=ckopsa"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── a fixture kind with an optional argument (arg-mode material) ────

(defhandler annotate-doc [row inp _ctx]
  (-> row
      (assoc-in [:data :note] (:note inp))
      (assoc-in [:data :note_visibility] (:visibility inp))))

(defresource bb-doc
  {:kind :bb_doc
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 100}]]
            [:note {:optional true} [:maybe [:string {:max 200}]]]
            [:note_visibility {:optional true} [:maybe [:string {:max 20}]]]]
   :filterable {:state #{:eq :in}}
   :actions
   {:annotate {:from #{:open} :to :open
               :input [:map
                       [:note [:string {:min 1 :max 200}]]
                       [:visibility {:optional true}
                        [:maybe [:string {:max 20}]]]]
               :safety {:idempotent true :reversible true :confirm false}
               :handler annotate-doc}
    :close {:from #{:open} :to :closed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A closed doc keeps its history."}}}})

;; ── the world ───────────────────────────────────────────────────────

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private tables
  ["meals" "plans" "bb_docs" "members" "roles" "grants" "approval_requests"
   "attachments" "subscriptions" "jobs" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  ;; approval_request enrolls on every
                                  ;; engine now (the merge item landed)
                                  :resources [fx/meal fx/plan bb-doc]})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- dev-headers
  ([id] (dev-headers id nil))
  ([id extra] (merge {"x-waymark-principal" id} extra)))

(def ^:private root (dev-headers "root"))

(def ^:private agent-headers
  (dev-headers "agent-7" {"x-waymark-actor-type" "agent"}))

(defn- scoped [grant-id]
  (assoc agent-headers "x-waymark-grant" grant-id))

(defn- req
  ([method uri] (req method uri nil root))
  ([method uri body] (req method uri body root))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path :headers (or headers {})}
            query (assoc :query-string query)
            body (assoc :body (if (string? body) body (wire/write-json body))))))))

(defn- json [resp] (some-> (:body resp) (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- body-str ^String [resp] (let [b (:body resp)]
                                 (if (string? b) b (slurp b))))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(def ^:private secret-note "TOPSECRET-note-77")

(def ^:private covered-plan
  {:start_date "2025-01-06" :weeks 1
   :notes secret-note
   :days [{:date "2025-01-06" :eating_out true}
          {:date "2025-01-07" :eating_out true}]})

(defn- offer-grant! [body]
  (let [resp (req :post "/api/grants" body)]
    (is (= 201 (:status resp)))
    (id-of resp)))

(defn- accept!
  ([gid] (accept! gid agent-headers))
  ([gid headers]
   (let [resp (req :post (str "/api/grants/" gid "/-/accept") nil headers)]
     (is (= 200 (:status resp)))
     (is (= "accepted" (:state (json resp)))))))

;; ── 1. field modes: deny-list ───────────────────────────────────────

(deftest field-deny-mode
  (let [pid (id-of (req :post "/api/plans" covered-plan))
        gid (offer-grant! {:audience "agent-7"
                           :scope [{:kind "plan"
                                    :actions ["assign_meal" "finalize"]
                                    :fields {:mode "deny"
                                             :names ["notes" "start_date"]}}]})]
    (accept! gid)
    (let [resp (req :get (str "/api/plans/" pid) nil (scoped gid))
          env (json resp)]
      (is (= 200 (:status resp)))
      (testing "redacted fields are absent from data — not nulled, absent"
        (is (not (contains? (:data env) :notes)))
        (is (not (contains? (:data env) :start_date)))
        (is (contains? (:data env) :weeks)))
      (testing "the redacted VALUE appears nowhere in the whole response"
        (is (not (str/includes? (body-str resp) secret-note))))
      (testing "THE HONESTY TRAP: the summary template reads start_date —
                the scoped summary is the honest generic line, no value"
        (is (= "Plan · Draft" (:summary env))))
      (testing "concealment discipline still holds beside the projection"
        (let [vs (conf/grant-concealment-violations env #{"assign_meal"
                                                          "finalize"})]
          (is (empty? vs) (str/join "\n" vs))))
      (testing "parts survive when their path is visible: the placed
                assign_meal still re-renders per day, key const-bound"
        (let [items (get-in env [:parts :days :items])]
          (is (= 2 (count items)))
          (is (= "2025-01-06"
                 (get-in (first items)
                         [:actions :assign_meal :input :properties :date :const]))))))
    (testing "the published schema view loses the fields too"
      (let [js (json (req :get "/api/schemas/plan" nil (scoped gid)))]
        (is (not (contains? (:properties js) :notes)))
        (is (not (contains? (:properties js) :start_date)))
        (is (contains? (:properties js) :weeks))
        (is (not-any? #(= "start_date" (str %)) (:required js))))
      (testing "…while the unscoped schema keeps them"
        (let [js (json (req :get "/api/schemas/plan"))]
          (is (contains? (:properties js) :notes))
          (is (contains? (:properties js) :start_date)))))
    (testing "collection items project identically — summary and all"
      (let [resp (req :get "/api/plans" nil (scoped gid))
            b (json resp)]
        (is (= 200 (:status resp)))
        (is (seq (get-in b [:data :items])))
        (is (every? #(= "Plan · Draft" (:summary %)) (get-in b [:data :items])))
        (is (not (str/includes? (body-str resp) secret-note)))))))

;; ── 2. field modes: allow-list, and the parts drop ──────────────────

(deftest field-allow-mode
  (let [pid (id-of (req :post "/api/plans" covered-plan))
        gid (offer-grant! {:audience "agent-7"
                           :scope [{:kind "plan"
                                    :actions ["finalize"]
                                    :fields {:mode "allow" :names ["weeks"]}}]})]
    (accept! gid)
    (let [resp (req :get (str "/api/plans/" pid) nil (scoped gid))
          env (json resp)]
      (is (= 200 (:status resp)))
      (testing "an allow-list renders exactly the named fields"
        (is (= #{:weeks} (set (keys (:data env))))))
      (testing "no redacted value anywhere: dates ride days AND
                start_date, both hidden"
        (is (not (str/includes? (body-str resp) "2025-01-06")))
        (is (not (str/includes? (body-str resp) secret-note))))
      (is (= "Plan · Draft" (:summary env)))
      (is (not (contains? env :parts)) "the parts group is redacted data")))
  (testing "a redacted part path drops the group even when its placed
            action is granted"
    (let [pid (id-of (req :post "/api/plans" covered-plan))
          gid (offer-grant! {:audience "agent-7"
                             :scope [{:kind "plan"
                                      :actions ["assign_meal"]
                                      :fields {:mode "deny" :names ["days"]}}]})]
      (accept! gid)
      (let [env (json (req :get (str "/api/plans/" pid) nil (scoped gid)))]
        (is (contains? (:actions env) :assign_meal)
            "the action itself stays granted and advertised")
        (is (not (contains? (:data env) :days)))
        (is (not (contains? env :parts)))))))

;; ── 3. argument modes ───────────────────────────────────────────────

(deftest arg-deny-mode
  (let [did (id-of (req :post "/api/bb_docs" {:title "Q3 report"}))
        gid (offer-grant! {:audience "agent-7"
                           :scope [{:kind "bb_doc"
                                    :actions ["annotate"]
                                    :args [{:action "annotate"
                                            :mode "deny"
                                            :names ["visibility"]}]}]})]
    (accept! gid)
    (let [env (json (req :get (str "/api/bb_docs/" did) nil (scoped gid)))]
      (testing "the advertised input loses the denied argument"
        (is (= #{:note}
               (set (keys (get-in env [:actions :annotate :input :properties])))))
        (is (not-any? #(= "visibility" (str %))
                      (get-in env [:actions :annotate :input :required])))))
    (testing "a denied arg in the body answers EXACTLY the unknown-field
              422 — indistinguishable from a field that never existed"
      (let [denied (req :post (str "/api/bb_docs/" did "/-/annotate")
                        {:note "hi" :visibility "wide"} (scoped gid))
            unknown (req :post (str "/api/bb_docs/" did "/-/annotate")
                         {:note "hi" :bogus "x"})]
        (is (= 422 (:status denied)))
        (is (= 422 (:status unknown)))
        (is (= (get-in (json unknown) [:errors :bogus])
               (get-in (json denied) [:errors :visibility]))
            "same words as malli's closed-map refusal")))
    (testing "dry-run enforces the same 422"
      (let [resp (req :post (str "/api/bb_docs/" did "/-/annotate?dry_run=1")
                      {:note "hi" :visibility "wide"} (scoped gid))]
        (is (= 422 (:status resp)))))
    (testing "the permitted shape still acts"
      (let [resp (req :post (str "/api/bb_docs/" did "/-/annotate")
                      {:note "hi"} (scoped gid))]
        (is (= 200 (:status resp)))
        (is (= "hi" (get-in (json resp) [:data :note])))))))

(deftest denying-a-required-arg-denies-the-action
  (let [did (id-of (req :post "/api/bb_docs" {:title "Q4 report"}))
        gid (offer-grant! {:audience "agent-7"
                           :scope [{:kind "bb_doc"
                                    :actions ["annotate" "close"]
                                    :args [{:action "annotate"
                                            :mode "allow"
                                            :names ["visibility"]}]}]})]
    (accept! gid)
    (let [env (json (req :get (str "/api/bb_docs/" did) nil (scoped gid)))]
      (testing "an unsatisfiable form is a lie: the action drops whole,
                concealment-style, while its granted sibling stays"
        (is (not (contains? (:actions env) :annotate)))
        (is (not (contains? (:unavailable env) :annotate)))
        (is (contains? (:actions env) :close))))
    (is (= 404 (:status (req :post (str "/api/bb_docs/" did "/-/annotate")
                             {:note "hi"} (scoped gid)))))))

;; ── 4. the negotiation machine ──────────────────────────────────────

(defn- grant-scope-count [gid]
  (count (get-in (json (req :get (str "/api/grants/" gid))) [:data :scope])))

(deftest negotiation-request-approve-deny
  (let [_ (req :post "/api/meals" {:name "Brisket night" :themes ["bbq"]})
        pid (id-of (req :post "/api/plans" covered-plan))
        gid (offer-grant! {:audience "agent-7"
                           :scope [{:kind "plan" :actions ["assign_meal"]}]})]
    (accept! gid)
    (is (= 404 (:status (req :get "/api/meals" nil (scoped gid))))
        "before the ask: meals do not exist for this principal")
    (let [made (req :post "/api/approval_requests"
                    {:grant_id gid
                     :task "Accept this week's meal suggestions"
                     :scope [{:kind "meal" :actions ["accept"]}]}
                    (scoped gid))
          rid (id-of made)]
      (is (= 201 (:status made)) "the scoped principal itself may ask")
      (is (= "agent-7" (get-in (json made) [:data :requested_by]))
          "the requester is stamped by the engine, not the body")
      (testing "four-eyes: the requester cannot judge its own ask"
        (let [resp (req :post (str "/api/approval_requests/" rid "/-/approve")
                        nil agent-headers)]
          (is (= 409 (:status resp)))
          (is (str/includes? (:detail (json resp)) "another principal"))))
      (testing "approval mints the access: system actor extends the grant,
                logged, and the affordance appears"
        (let [resp (req :post (str "/api/approval_requests/" rid "/-/approve"))]
          (is (= 200 (:status resp)))
          (is (= "approved" (:state (json resp))))
          (is (= "root" (get-in (json resp) [:data :approved_by]))))
        (is (= 2 (grant-scope-count gid)))
        (let [ts (store/with-tx (:storage *eng*)
                   (fn [tx] (store/transitions (:storage *eng*) tx
                                               {:kind :grant
                                                :resource-id gid} {})))
              ext (first (filter #(= :extend (:action %)) ts))]
          (is (some? ext) "the extension is a logged transition")
          (is (= (:id grants/approvals-actor) (get-in ext [:actor :id]))))
        (let [env (json (req :get "/api/meals" nil (scoped gid)))]
          (is (seq (get-in env [:data :items])) "meals exist now"))
        (let [mid (id-of (req :post "/api/meals" {:name "Ribs" :themes ["bbq"]}))
              env (json (req :get (str "/api/meals/" mid) nil (scoped gid)))]
          (let [vs (conf/grant-concealment-violations env #{"accept"})]
            (is (empty? vs) (str/join "\n" vs)))))
      (testing "a natural replay of approve does not extend twice"
        (is (= 200 (:status (req :post (str "/api/approval_requests/" rid
                                            "/-/approve")))))
        (is (= 2 (grant-scope-count gid)))))
    (testing "deny leaves the 404s standing"
      (let [rid (id-of (req :post "/api/approval_requests"
                            {:grant_id gid
                             :task "Retire stale meals"
                             :scope [{:kind "meal" :actions ["retire"]}]}
                            (scoped gid)))
            resp (req :post (str "/api/approval_requests/" rid "/-/deny")
                      {:note "Retiring is a person's call."})]
        (is (= 200 (:status resp)))
        (is (= "denied" (:state (json resp))))
        (is (= "Retiring is a person's call." (get-in (json resp) [:data :note])))
        (let [mid (id-of (req :post "/api/meals" {:name "Wings" :themes ["bbq"]}))]
          (is (= 404 (:status (req :post (str "/api/meals/" mid "/-/retire")
                                   nil (scoped gid))))))))
    (testing "a request may not name a grant its requester does not hold"
      (let [other (offer-grant! {:audience "agent-8"
                                 :scope [{:kind "plan" :actions ["finalize"]}]})
            resp (req :post "/api/approval_requests"
                      {:grant_id other :task "Sneak scope onto a stranger"
                       :scope [{:kind "meal" :actions ["accept"]}]}
                      (scoped gid))]
        (is (= 409 (:status resp)))))
    (testing "a dead grant no longer accepts scope"
      (let [g2 (offer-grant! {:audience "agent-7"
                              :scope [{:kind "plan" :actions ["finalize"]}]})
            _ (accept! g2)
            rid (id-of (req :post "/api/approval_requests"
                            {:grant_id g2 :task "More, please"
                             :scope [{:kind "meal" :actions ["accept"]}]}
                            (scoped g2)))
            _ (req :post (str "/api/grants/" g2 "/-/revoke"))
            resp (req :post (str "/api/approval_requests/" rid "/-/approve"))]
        (is (= 409 (:status resp)))
        (is (str/includes? (:detail (json resp)) "no longer accepts"))))
    (testing "the extend transition itself is concealed from the wire"
      (let [env (json (req :get (str "/api/grants/" gid)))]
        (is (not (contains? (:actions env) :extend)))
        (is (not (contains? (:unavailable env) :extend))))
      (is (= 404 (:status (req :post (str "/api/grants/" gid "/-/extend")
                               {:scope [{:kind "meal" :actions ["accept"]}]})))))))

;; ── 5. the own-grant surface ────────────────────────────────────────

(deftest own-grant-surface
  (let [g1 (offer-grant! {:audience "own-agent"
                          :scope [{:kind "plan" :actions ["finalize"]}]})
        g2 (offer-grant! {:audience "someone-else"
                          :scope [{:kind "plan" :actions ["finalize"]}]})
        own (dev-headers "own-agent" {"x-waymark-actor-type" "agent"})
        own-scoped (assoc own "x-waymark-grant" g1)]
    (accept! g1 own)
    (let [rid (id-of (req :post "/api/approval_requests"
                          {:grant_id g1 :task "See the meals"
                           :scope [{:kind "meal" :actions ["accept"]}]}
                          own-scoped))]
      (testing "the scoped principal reads its own grant — and only its own"
        (let [resp (req :get (str "/api/grants/" g1) nil own-scoped)
              env (json resp)]
          (is (= 200 (:status resp)))
          (is (= "own-agent" (get-in env [:data :audience])))
          (is (= {} (:actions env)) "no grant action is LIVE here")
          (is (= #{:accept :revoke} (set (keys (:unavailable env))))
              "the own-surface affordances narrate honestly: accept
               out of state on an accepted grant, revoke refused by
               no-self-dealing — visible refusals, never mute 404s
               (waymark-rci — no unscoped moment exists for either)"))
        (is (= 404 (:status (req :get (str "/api/grants/" g2) nil own-scoped)))))
      (testing "the collection narrows by the visibility cond, total honest"
        (let [b (json (req :get "/api/grants" nil own-scoped))]
          (is (= 1 (get-in b [:data :total])))
          (is (= [(str "/api/grants/" g1)]
                 (mapv :self (get-in b [:data :items]))))
          (is (not (contains? (:actions b) :create))
              "offering grants is not the audience's affordance")))
      (testing "its approval requests too — create is the one affordance"
        (let [b (json (req :get "/api/approval_requests" nil own-scoped))]
          (is (= 1 (get-in b [:data :total])))
          (is (= [(str "/api/approval_requests/" rid)]
                 (mapv :self (get-in b [:data :items]))))
          (is (contains? (:actions b) :create)))
        (let [env (json (req :get (str "/api/approval_requests/" rid)
                             nil own-scoped))]
          (is (= {} (:actions env)) "approve/deny are not the requester's")))
      (testing "another principal's requests do not exist for it"
        (let [foreign (id-of (req :post "/api/approval_requests"
                                  {:grant_id g2 :task "Their business"
                                   :scope [{:kind "meal" :actions ["accept"]}]}
                                  (dev-headers "someone-else")))]
          (is (= 404 (:status (req :get (str "/api/approval_requests/" foreign)
                                  nil own-scoped))))))
      (testing "well-known lists the own surface beside the granted —
                the negotiation kinds, the jobs the principal asked for,
                and (waymark-0k4) the recipe proposals it staged itself"
        (let [b (json (req :get "/api/.well-known/waymark" nil own-scoped))]
          (is (= #{"approval_request" "grant" "job" "plan"
                   "recipe_proposal" "feed_view" "feed_view_consent"}
                 (set (:kinds b))))))
      (testing "the own surface survives the grant's death — how a dead
                grant's holder asks again"
        (is (= 200 (:status (req :post (str "/api/grants/" g1 "/-/revoke")))))
        (is (= 404 (:status (req :get "/api/plans" nil own-scoped)))
            "the domain scopes to nothing")
        (is (= 200 (:status (req :get (str "/api/grants/" g1) nil own-scoped))))
        (is (= 201 (:status (req :post "/api/approval_requests"
                                 {:grant_id g1 :task "Asking again"
                                  :scope [{:kind "plan" :actions ["finalize"]}]}
                                 own-scoped))))))))

;; ── 6. scope honesty (waymark-vnc): asks and grants speak real names ─

(deftest scope-names-real-things
  (let [gid (offer-grant! {:audience "agent-7"
                           :scope [{:kind "plan" :actions ["assign_meal"]}]})]
    (accept! gid)
    (testing "an ask naming a nonexistent action refuses NOW, spelling
              the kind's real actions in the reason"
      (let [resp (req :post "/api/approval_requests"
                      {:grant_id gid
                       :task "Update the details"
                       :scope [{:kind "meal" :actions ["update_details"]}]}
                      (scoped gid))
            b (json resp)]
        (is (= 409 (:status resp)))
        (is (= "scope-names-real-actions" (:guard b)))
        (is (str/includes? (:detail b) "update_details")
            "the refusal names the action that does not exist")
        (is (str/includes? (:detail b) "meal"))
        (is (and (str/includes? (:detail b) "accept")
                 (str/includes? (:detail b) "retire"))
            "the reason spells the kind's REAL action vocabulary")))
    (testing "an ask naming a nonexistent kind refuses"
      (let [resp (req :post "/api/approval_requests"
                      {:grant_id gid
                       :task "See the unicorns"
                       :scope [{:kind "unicorn" :actions []}]}
                      (scoped gid))
            b (json resp)]
        (is (= 409 (:status resp)))
        (is (= "scope-names-real-kinds" (:guard b)))
        (is (str/includes? (:detail b) "unicorn"))))
    (testing "a valid ask is unaffected"
      (is (= 201 (:status (req :post "/api/approval_requests"
                               {:grant_id gid
                                :task "Accept the suggestions"
                                :scope [{:kind "meal" :actions ["accept"]}]}
                               (scoped gid))))))
    (testing "the read-only ask (actions []) stays legal"
      (is (= 201 (:status (req :post "/api/approval_requests"
                               {:grant_id gid
                                :task "Just read the meals"
                                :scope [{:kind "meal" :actions []}]}
                               (scoped gid))))))
    (testing "the hand-offered grant meets the same gate"
      (let [resp (req :post "/api/grants"
                      {:audience "agent-7"
                       :scope [{:kind "meal" :actions ["update_details"]}]})
            b (json resp)]
        (is (= 409 (:status resp)))
        (is (= "scope-names-real-actions" (:guard b)))
        (is (str/includes? (:detail b) "accept")))
      (let [resp (req :post "/api/grants"
                      {:audience "agent-7"
                       :scope [{:kind "unicorn" :actions []}]})]
        (is (= 409 (:status resp)))
        (is (= "scope-names-real-kinds" (:guard (json resp))))))
    (testing "upgrade honesty: a STORED grant whose action later
              disappeared loads, boots and reads fine — the stale pair
              simply never matches (no retroactive sweep)"
      ;; the mint door skips create guards — exactly a pre-validation
      ;; row surviving an upgrade that removed the action
      (inv/create! *eng* :grant
                   {:audience "agent-7"
                    :scope [{:kind "meal" :actions ["gone_action"]}
                            {:kind "meal" :actions ["accept"]}]}
                   {:principal grants/approvals-actor
                    :id "grant-stale-vnc"
                    :mint? true})
      (accept! "grant-stale-vnc")
      (let [env (json (req :get "/api/meals" nil (scoped "grant-stale-vnc")))]
        (is (some? (get-in env [:data :items]))
            "the kind still renders under the stale-bearing grant"))
      (let [mid (id-of (req :post "/api/meals" {:name "Brisket" :themes ["bbq"]}))
            env (json (req :get (str "/api/meals/" mid)
                           nil (scoped "grant-stale-vnc")))]
        (is (not (contains? (:actions env) :gone_action))
            "the stale action is nowhere advertised")
        (is (= 404 (:status (req :post (str "/api/meals/" mid "/-/gone_action")
                                 nil (scoped "grant-stale-vnc"))))
            "and invoking it draws the same 404 it always did")))))
