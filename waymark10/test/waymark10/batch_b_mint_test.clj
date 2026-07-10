(ns waymark10.batch-b-mint-test
  "The mint fix — the negotiation machine's original purpose restored,
  over the real ring handler: an agent with NO grant asks for the
  access it needs, a human approves (four-eyes), and the approval
  MINTS the grant — audience = requester, scope = exactly the
  approved ask, accepted through the machine's own accept, replay-
  safe. Deny leaves nothing. The abuse surface is guarded (pacing,
  the open-asks cap, refusals with sentences), the asking door is
  discoverable from every named principal's scoped request, and the
  concealment constraint holds: an ungranted kind's 404 is
  byte-pinned — no request-access remedy ever leaks existence.
  Needs its own database; export
  WAYMARK10_TEST_DSN=jdbc:postgresql://localhost:5433/waymark10_grants_test?user=ckopsa"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private tables
  ["meals" "plans" "members" "roles" "grants" "approval_requests"
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
                                  :resources [fx/meal fx/plan]})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- dev-headers
  ([id] (dev-headers id nil))
  ([id extra] (merge {"x-waymark-principal" id} extra)))

(def ^:private root (dev-headers "root"))

(defn- agent-headers [id]
  (dev-headers id {"x-waymark-actor-type" "agent"}))

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

(def ^:private covered-plan
  {:start_date "2025-01-06" :weeks 1
   :days [{:date "2025-01-06" :eating_out true}
          {:date "2025-01-07" :eating_out true}]})

(defn- ask!
  "File an anchorless ask as the named agent; returns the response."
  [agent-id scope task]
  (req :post "/api/approval_requests"
       {:task task :scope scope}
       (agent-headers agent-id)))

(defn- grant-transitions [gid]
  (store/with-tx (:storage *eng*)
    (fn [tx] (store/transitions (:storage *eng*) tx
                                {:kind :grant :resource-id gid} {}))))

;; ── 1. the mint flow: from zero to a scoped surface ─────────────────

(deftest mint-flow-from-zero
  (let [pid (id-of (req :post "/api/plans" covered-plan))
        made (ask! "agent-9" [{:kind "plan" :actions ["assign_meal"]}]
                   "Fill this week's dinner slots")
        env (json made)
        rid (id-of made)]
    (is (= 201 (:status made)) "a zero-grant agent may ask — no anchor required")
    (is (= "agent-9" (:requested_by (:data env)))
        "the requester is stamped by the engine, not the body")
    (is (nil? (:grant_id (:data env))) "an anchorless ask names no grant yet")
    (testing "four-eyes: the requester cannot judge its own ask"
      (let [resp (req :post (str "/api/approval_requests/" rid "/-/approve")
                      nil (agent-headers "agent-9"))]
        (is (= 409 (:status resp)))
        (is (str/includes? (:detail (json resp)) "another principal"))))
    (testing "approval mints the grant: created and accepted through the
              machine, system actor, the id stamped on the ask"
      (let [resp (req :post (str "/api/approval_requests/" rid "/-/approve"))
            approved (json resp)
            gid (get-in approved [:data :grant_id])]
        (is (= 200 (:status resp)))
        (is (= "approved" (:state approved)))
        (is (= "root" (get-in approved [:data :approved_by])))
        (is (= (str "grant-" rid) gid)
            "the minted grant's name derives from the ask — replay restamps the same")
        (let [g (json (req :get (str "/api/grants/" gid)))]
          (is (= "accepted" (:state g)) "accepted on mint: the ask was the consent")
          (is (= "agent-9" (get-in g [:data :audience])))
          (let [scope (get-in g [:data :scope])]
            (is (= 1 (count scope)) "scope is exactly the approved ask")
            (is (= "plan" (:kind (first scope))))
            (is (= ["assign_meal"] (:actions (first scope))))))
        (let [ts (grant-transitions gid)]
          (is (= #{:create :accept} (into #{} (map :action) ts)))
          (is (= 2 (count ts)))
          (is (every? #(= (:id grants/approvals-actor) (get-in % [:actor :id])) ts)
              "both transitions are the approvals actor's, logged"))
        (testing "the requester presents the minted grant and is scoped in"
          (let [scoped (assoc (agent-headers "agent-9") "x-waymark-grant" gid)
                plan-env (json (req :get (str "/api/plans/" pid) nil scoped))]
            (is (contains? (:actions plan-env) :assign_meal)
                "the affordance appears")
            (let [vs (conf/grant-concealment-violations plan-env
                                                        #{"assign_meal"})]
              (is (empty? vs) (str/join "\n" vs)))
            (is (= 404 (:status (req :get "/api/meals" nil scoped)))
                "nothing beyond the ask exists")))
        (testing "approve replays do not mint twice — natural and keyed alike"
          (is (= 200 (:status (req :post (str "/api/approval_requests/" rid
                                              "/-/approve")))))
          (let [k (assoc root "idempotency-key" "mint-redelivery-1")]
            (is (= 200 (:status (req :post (str "/api/approval_requests/" rid
                                                "/-/approve") nil k)))))
          (is (= 2 (count (grant-transitions gid)))
              "the grant's log still holds exactly create + accept"))))))

;; ── 2. deny leaves nothing ──────────────────────────────────────────

(deftest deny-leaves-nothing
  (let [made (ask! "denied-agent" [{:kind "plan" :actions ["finalize"]}]
                   "Finalize the week")
        rid (id-of made)
        resp (req :post (str "/api/approval_requests/" rid "/-/deny")
                  {:note "Finalizing is a person's call."})]
    (is (= 201 (:status made)))
    (is (= 200 (:status resp)))
    (is (= "denied" (:state (json resp))))
    (is (nil? (get-in (json resp) [:data :grant_id]))
        "a denied ask never learns a grant name")
    (let [b (json (req :get "/api/grants?audience=denied-agent"))]
      (is (zero? (get-in b [:data :total])) "no grant exists — nothing was minted"))
    (is (= 404 (:status (req :get "/api/plans" nil
                             (assoc (agent-headers "denied-agent")
                                    "x-waymark-grant" (str "grant-" rid)))))
        "presenting the never-minted name scopes to nothing")))

;; ── 3. the abuse surface: the open-asks cap ─────────────────────────

(deftest open-asks-are-capped
  (let [asks (mapv (fn [n]
                     (ask! "eager-agent" [{:kind "plan" :actions ["finalize"]}]
                           (str "Ask number " n)))
                   (range grants/open-asks-cap))
        rids (mapv id-of asks)]
    (is (every? #(= 201 (:status %)) asks))
    (testing "the cap refuses the next ask, naming the pending ones"
      (let [resp (ask! "eager-agent" [{:kind "plan" :actions ["finalize"]}]
                       "One over the cap")
            detail (:detail (json resp))]
        (is (= 409 (:status resp)))
        (is (str/includes? detail
                           (str "capped at " grants/open-asks-cap)))
        (doseq [rid rids]
          (is (str/includes? detail rid) "every pending ask is named"))))
    (testing "a verdict on one reopens the door"
      (is (= 200 (:status (req :post (str "/api/approval_requests/" (first rids)
                                          "/-/deny")
                               {:note "No."}))))
      (is (= 201 (:status (ask! "eager-agent"
                                [{:kind "plan" :actions ["finalize"]}]
                                "Asking again after a verdict")))))))

;; ── 4. the abuse surface: pacing ────────────────────────────────────

(deftest fresh-asks-are-paced
  ;; fill the hour's budget, keeping the open count under the cap by
  ;; denying as we go — denied asks stay anchorless and still count
  ;; against the window
  (let [file! (fn [n] (ask! "busy-agent" [{:kind "plan" :actions ["reopen"]}]
                            (str "Paced ask " n)))
        first-half (mapv file! (range 10))]
    (is (every? #(= 201 (:status %)) first-half))
    (doseq [resp first-half]
      (is (= 200 (:status (req :post (str "/api/approval_requests/"
                                          (id-of resp) "/-/deny")
                               {:note "Testing the pace."})))))
    (let [second-half (mapv file! (range 10 grants/ask-pace-limit))]
      (is (every? #(= 201 (:status %)) second-half)
          "the budget itself is generous — all twenty land"))
    (testing "the twenty-first refuses with the pacing sentence"
      (let [resp (file! 21)
            b (json resp)]
        (is (= 409 (:status resp)))
        (is (str/includes? (:detail b)
                           (str "paced to " grants/ask-pace-limit " an hour")))
        (is (str/includes? (:detail b) "the window reopens at 2")
            "the refusal says when the window reopens (an instant, not a hole)")))))

;; ── 5. the concealment constraint, byte-pinned ──────────────────────

(def ^:private ungranted-404
  "The 404 an ungranted kind answers a scoped request. PINNED BYTES:
  this body must never grow a request-access remedy (or anything
  else) — a hint would leak the kind's existence to a principal it is
  concealed from. Discoverability lives on the negotiation surface
  instead."
  (str "{\"type\":\"https://waymark.dev/problems/not-found\","
       "\"status\":404,\"title\":\"Not found\","
       "\"detail\":\"No collection \\\"meals\\\".\"}"))

(deftest concealment-404-byte-pin
  (let [gid (id-of (req :post "/api/grants"
                        {:audience "pin-agent"
                         :scope [{:kind "plan" :actions ["finalize"]}]}))
        _ (req :post (str "/api/grants/" gid "/-/accept") nil
               (agent-headers "pin-agent"))
        live (req :get "/api/meals" nil
                  (assoc (agent-headers "pin-agent") "x-waymark-grant" gid))
        dead (req :get "/api/meals" nil
                  (assoc (agent-headers "pin-agent")
                         "x-waymark-grant" "no-such-grant"))]
    (is (= 404 (:status live)))
    (is (= 404 (:status dead)))
    (is (= ungranted-404 (body-str live))
        "a live grant's ungranted kind: the pinned bytes, nothing more")
    (is (= ungranted-404 (body-str dead))
        "a dead grant answers the very same bytes")))

;; ── 6. discoverability: the asking door on every scoped request ─────

(deftest negotiation-surface-discoverability
  (let [scoped (assoc (agent-headers "boot-agent")
                      "x-waymark-grant" "never-minted")]
    (testing "a named principal behind a dead grant still sees the door"
      (let [b (json (req :get "/api/.well-known/waymark" nil scoped))]
        (is (= ["approval_request" "grant"] (:kinds b))))
      (is (= 404 (:status (req :get "/api/plans" nil scoped)))
          "the domain stays concealed")
      (let [b (json (req :get "/api/approval_requests" nil scoped))]
        (is (zero? (get-in b [:data :total])))
        (is (contains? (:actions b) :create)
            "filing an ask is the one affordance")))
    (testing "and may file through it, scoped"
      (let [resp (req :post "/api/approval_requests"
                      {:task "Boot me in"
                       :scope [{:kind "plan" :actions ["finalize"]}]}
                      scoped)]
        (is (= 201 (:status resp)))
        (is (= "boot-agent" (get-in (json resp) [:data :requested_by])))))
    (testing "anonymous stays outside: the door is for NAMED principals"
      (let [b (json (req :get "/api/.well-known/waymark" nil
                        {"x-waymark-grant" "never-minted"}))]
        (is (= [] (:kinds b))))
      (is (= 404 (:status (req :post "/api/approval_requests"
                               {:task "?" :scope [{:kind "plan"
                                                   :actions ["finalize"]}]}
                               {"x-waymark-grant" "never-minted"})))
          "an anonymous scoped create does not exist")
      (let [resp (req :post "/api/approval_requests"
                      {:task "?" :scope [{:kind "plan" :actions ["finalize"]}]}
                      {})]
        (is (= 409 (:status resp))
            "an anonymous unscoped create refuses — it would grant nobody")
        (is (str/includes? (:detail (json resp)) "anonymous"))))))

;; ── 7. the extension path, unmoved ──────────────────────────────────

(deftest extension-path-regression
  (let [_ (req :post "/api/meals" {:name "Brisket night" :themes ["bbq"]})
        gid (id-of (req :post "/api/grants"
                        {:audience "agent-7"
                         :scope [{:kind "plan" :actions ["assign_meal"]}]}))
        _ (req :post (str "/api/grants/" gid "/-/accept") nil
               (agent-headers "agent-7"))
        scoped (assoc (agent-headers "agent-7") "x-waymark-grant" gid)
        made (req :post "/api/approval_requests"
                  {:grant_id gid
                   :task "See the meals too"
                   :scope [{:kind "meal" :actions ["accept"]}]}
                  scoped)
        rid (id-of made)]
    (is (= 201 (:status made)) "an anchored ask still files")
    (is (= 404 (:status (req :get "/api/meals" nil scoped))))
    (let [resp (req :post (str "/api/approval_requests/" rid "/-/approve"))]
      (is (= 200 (:status resp)))
      (is (= gid (get-in (json resp) [:data :grant_id]))
          "the anchored ask keeps its own grant — nothing is minted"))
    (let [g (json (req :get (str "/api/grants/" gid)))]
      (is (= 2 (count (get-in g [:data :scope])))
          "approval extended the named grant, exactly as before"))
    (is (= 200 (:status (req :get "/api/meals" nil scoped)))
        "the widened surface exists now")
    (testing "a stranger's grant still cannot be named"
      (let [other (id-of (req :post "/api/grants"
                              {:audience "agent-8"
                               :scope [{:kind "plan" :actions ["finalize"]}]}))
            resp (req :post "/api/approval_requests"
                      {:grant_id other :task "Sneak scope onto a stranger"
                       :scope [{:kind "meal" :actions ["accept"]}]}
                      scoped)]
        (is (= 409 (:status resp)))))))
