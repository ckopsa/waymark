(ns waymark10.batch-b-members-test
  "Batch B acceptance — invite → bind membership over the real ring
  handler: an admin's token-bearing create is born :invited; the
  first authenticated principal presenting the token (X-Waymark-
  Invite) binds through the concealed :bind (registrar system actor,
  logged); the second binder is refused; an invited-only engine
  ({:members :invited-only}) refuses unknown principals instead of
  auto-provisioning, while the default engine keeps provisioning.
  Needs the batch database; export
  WAYMARK10_TEST_DSN=jdbc:postgresql://localhost:5433/waymark10_b_test?user=ckopsa"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(def ^:dynamic *eng* nil)         ; the default-mode engine
(def ^:dynamic *h* nil)           ; …and its handler
(def ^:dynamic *hi* nil)          ; the SAME engine, invited-only

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
        (let [eng (engine/engine {:storage st :resources [fx/meal]})]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    ;; the mode is one engine key ({:members
                    ;; :invited-only}); the whitelist line in
                    ;; engine/engine is the recorded one-line merge item
                    *hi* (engine/handler (assoc eng :members :invited-only))]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- headers-for
  ([id] (headers-for id nil))
  ([id extra] (merge {"x-waymark-principal" id} extra)))

(def ^:private admin
  ;; system principals bypass the gate — the bootstrap admin of an
  ;; invited-only engine is the operator's own actor
  (headers-for "admin" {"x-waymark-actor-type" "system"}))

(defn- req*
  [h method uri body headers]
  (h (cond-> {:request-method method :uri uri :headers (or headers {})}
       body (assoc :body (wire/write-json body)))))

(defn- invited [method uri & [body headers]] (req* *hi* method uri body headers))
(defn- default [method uri & [body headers]] (req* *h* method uri body headers))

(defn- json [resp] (some-> (:body resp) (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(def ^:private token "tok-priya-8827")

(defn- invite!
  "The admin's invite: display + binding token (+ roles), born :invited."
  [display tok roles]
  (let [resp (invited :post "/api/members"
                      (cond-> {:display display :actor_type "human"
                               :bind_token tok}
                        roles (assoc :roles roles))
                      admin)]
    (is (= 201 (:status resp)))
    (is (= "invited" (:state (json resp))))
    (is (= "admin" (get-in (json resp) [:data :invited_by]))
        "the inviter is recorded at birth")
    (id-of resp)))

;; ── the flow ────────────────────────────────────────────────────────

(deftest invited-only-binds-once
  (is (= 201 (:status (invited :post "/api/roles" {:name "planner"} admin))))
  (let [mid (invite! "Priya" token ["planner"])]
    (testing "an unknown principal without a token is refused — membership
              is the admin's to extend, not the resolver's"
      (let [resp (invited :get "/api/meals" nil (headers-for "stranger"))
            b (json resp)]
        (is (= 403 (:status resp)))
        (is (str/ends-with? (str (:type b)) "membership-invited"))
        (let [vs (conf/problem-violations (:status resp)
                                          (get-in resp [:headers "Content-Type"])
                                          b {:where "invited-only stranger"})]
          (is (empty? vs) (str/join "\n" vs)))))
    (testing "first authenticated sight with the token binds: token → id
              claim, an audited transition by the registrar"
      (let [resp (invited :get "/api/meals" nil
                          (headers-for "priya-oidc-sub"
                                       {"x-waymark-invite" token}))]
        (is (= 200 (:status resp))))
      (let [env (json (invited :get (str "/api/members/" mid) nil admin))]
        (is (= "active" (:state env)))
        (is (= "priya-oidc-sub" (get-in env [:data :subject]))))
      (let [ts (store/with-tx (:storage *eng*)
                 (fn [tx] (store/transitions (:storage *eng*) tx
                                             {:kind :member
                                              :resource-id mid} {})))
            bind (first (filter #(= :bind (:action %)) ts))]
        (is (some? bind))
        (is (= (:id members/registrar) (get-in bind [:actor :id])))))
    (testing "the bound member needs no token afterwards, and carries the
              invite's roles"
      (is (= 200 (:status (invited :get "/api/meals" nil
                                   (headers-for "priya-oidc-sub")))))
      (is (contains? (:roles (members/gate!
                              (assoc *eng* :members :invited-only)
                              (t/principal {:id "priya-oidc-sub"})))
                     "planner")))
    (testing "the second binder is refused: the token is spent"
      (let [resp (invited :get "/api/meals" nil
                          (headers-for "mallory"
                                       {"x-waymark-invite" token}))]
        (is (= 403 (:status resp)))
        (is (str/ends-with? (str (:type (json resp))) "membership-invited"))))
    (testing "suspension gates the bound identity like any member"
      (is (= 200 (:status (invited :post (str "/api/members/" mid "/-/suspend")
                                   nil admin))))
      (let [resp (invited :get "/api/meals" nil (headers-for "priya-oidc-sub"))]
        (is (= 403 (:status resp)))
        (is (str/ends-with? (str (:type (json resp))) "member-suspended")))
      (is (= 200 (:status (invited :post (str "/api/members/" mid "/-/reinstate")
                                   nil admin)))))))

(deftest bind-is-concealed-and-auto-provision-stays-the-default
  (let [mid (invite! "Sam" "tok-sam-9911" nil)]
    (testing "bind is the registrar's, concealed: absent from a human's
              envelope, 404 when invoked by hand"
      ;; the DEFAULT handler serves the same storage; a human admin
      ;; reads the invited row there without tripping the invited gate
      (let [env (json (default :get (str "/api/members/" mid) nil
                               (headers-for "root")))]
        (is (= "invited" (:state env)))
        (is (not (contains? (:actions env) :bind)))
        (is (not (contains? (:unavailable env) :bind))))
      (is (= 404 (:status (default :post (str "/api/members/" mid "/-/bind")
                                   {:subject "by-hand"}
                                   (headers-for "root"))))))
    (testing "binding works under the default mode too — the mode only
              decides the unknown-principal fallback"
      (is (= 200 (:status (default :get "/api/meals" nil
                                   (headers-for "sam-sub"
                                                {"x-waymark-invite" "tok-sam-9911"})))))
      (is (= "active" (:state (json (default :get (str "/api/members/" mid)
                                             nil (headers-for "root")))))))
    (testing "auto-provision remains the default for unknowns"
      (is (= 200 (:status (default :get "/api/meals" nil (headers-for "walkin")))))
      (is (= 200 (:status (default :get "/api/members/walkin" nil
                                   (headers-for "root"))))))))

;; ── provenance: the honest identity key at every birth (waymark-4zj.9.1) ─

(deftest provenance-is-stamped-at-each-birth-path
  (testing "provision! (IdP/Bearer first-sight, via gate!'s auto-provision)
            stamps 'idp' — a durable identity"
    (is (= 200 (:status (default :get "/api/meals" nil
                                 (headers-for "idp-first-sight")))))
    (is (= "idp" (get-in (json (default :get "/api/members/idp-first-sight"
                                        nil (headers-for "root")))
                         [:data :provenance]))))
  (testing "a token-bearing admin create (an INVITE) stamps 'invite'"
    (let [mid (invite! "Provenance Invitee" "tok-prov-invite-01" nil)]
      (is (= "invite" (get-in (json (invited :get (str "/api/members/" mid)
                                             nil admin))
                              [:data :provenance])))))
  (testing "knock! (the self-service /agentInvite) stamps 'knock' — a guest —
            and KEEPS it across its later bind (bind-agent! does not touch it)"
    (reset! members/knock-log [])
    (let [krow (members/knock! *eng* {:display "Prov Knocker"})]
      (is (= "knock" (get-in krow [:data :provenance])))
      (is (= :invited (:state krow)))
      (let [bound (members/bind-agent! *eng* (get-in krow [:data :bind_token]))]
        (is (= :active (:state bound)))
        (is (= "knock" (get-in bound [:data :provenance]))
            "the bind welds the principal but never rewrites provenance")))))

(deftest provenance-cannot-be-set-by-hand
  ;; the :subject / reentry_token precedent: provenance is the offer_reentry
  ;; durable guard's key, so a hand-set "idp" would forge a way home. Only
  ;; the birth path writes it — a create or edit carrying it is refused.
  (testing "a create carrying provenance is refused — never a 201"
    (let [resp (default :post "/api/members"
                        {:display "forger" :actor_type "agent"
                         :provenance "idp"}
                        (headers-for "forger"))]
      (is (not= 201 (:status resp)) (pr-str (json resp)))
      (is (contains? #{409 422} (:status resp)))))
  (testing "an honest create — no provenance in the body — still lands, and
            the birth path stamps it"
    (let [resp (default :post "/api/members"
                        {:display "honest-agent" :actor_type "agent"}
                        admin)]
      (is (= 201 (:status resp)))
      (is (= "idp" (get-in (json resp) [:data :provenance])))))
  (testing "no action's closed input can smuggle provenance either: a
            knock-born guest stays 'knock' even when a set_handle carries a
            provenance key (the input map is closed to :handle)"
    (reset! members/knock-log [])
    (let [krow (members/knock! *eng* {:display "knock-editee"})
          mid (:id (members/bind-agent! *eng* (get-in krow [:data :bind_token])))]
      (default :post (str "/api/members/" mid "/-/set_handle")
               {:handle "knock-editee" :provenance "idp"} admin)
      (is (= "knock" (get-in (json (default :get (str "/api/members/" mid)
                                            nil admin))
                             [:data :provenance]))
          "provenance is untouched — no edit path can forge a durable identity"))))

;; ── the gate heals what it finds (waymark-tti.10) ────────────────────
;;
;; provision! stamps the rows it MINTS; a row that predates the stamp
;; had no door to gain one (:bind is :from #{:invited} and stays
;; there), so letters' deliverability read a real inhabitant as
;; nobody. The gate now stamps what it FINDS — but ONLY the by-id
;; branch, only :active, only once, and never at the cost of a
;; request.

(defn- member-row [id]
  (store/with-tx (:storage *eng*)
    (fn [tx] (store/load-row (:storage *eng*) tx :member id {}))))

(defn- member-transitions [id]
  (store/with-tx (:storage *eng*)
    (fn [tx] (store/transitions (:storage *eng*) tx
                                {:kind :member :resource-id id} {}))))

(defn- legacy-row!
  "The roster row as prod holds them: :active, minted with an explicit
  id, and NO :subject — the shape a human's hand-added member wears,
  and the shape every first-sight row wore before provision! stamped
  itself. Created straight through the engine because no door mints
  one any more."
  [id display]
  (inv/create! *eng* :member {:display display :actor_type "human"}
               {:principal members/registrar :id id})
  (is (nil? (get-in (member-row id) [:data :subject]))
      "the fixture starts unstamped, or it proves nothing")
  id)

(deftest gate-heals-a-legacy-active-row-once
  (let [id (legacy-row! "legacy-colton" "Legacy Colton")]
    (testing "first sight stamps the subject — the row's own id, which IS
              the principal id that resolved it; the write is the
              registrar's, through the engine, and audited"
      (is (= 200 (:status (default :get "/api/meals" nil (headers-for id)))))
      (is (= id (get-in (member-row id) [:data :subject])))
      (let [stamp (first (filter #(= :stamp_subject (:action %))
                                 (member-transitions id)))]
        (is (some? stamp) "the heal is a transition, not a silent write")
        (is (= (:id members/registrar) (get-in stamp [:actor :id])))))
    (testing "and it never writes again — gate! runs on EVERY request, so a
              per-request write would be the regression this must not be"
      (let [before (count (member-transitions id))
            version (:version (member-row id))]
        (dotimes [_ 3]
          (is (= 200 (:status (default :get "/api/meals" nil (headers-for id))))))
        (is (= before (count (member-transitions id)))
            "three more requests, not one more transition")
        (is (= version (:version (member-row id)))
            "…and not one more row version")))))

(deftest the-heal-is-the-gates-alone-never-a-hand
  ;; :stamp_subject wears registrar-binds, the guard :bind wears: system
  ;; principals only, :hide true. So it is not an affordance anyone can
  ;; see or spend — a human, and a human wearing recovery-admin, meet
  ;; the same 404 the concealed :bind gives.
  (let [id (legacy-row! "conceal-probe" "Conceal Probe")
        env (json (default :get (str "/api/members/" id) nil (headers-for "root")))]
    (is (= "active" (:state env)))
    (is (not (contains? (:actions env) :stamp_subject))
        "absent from the envelope's actions")
    (is (not (contains? (:unavailable env) :stamp_subject))
        "…and absent from unavailable too: concealed, not refused")
    (is (= 404 (:status (default :post (str "/api/members/" id "/-/stamp_subject")
                                 nil (headers-for "root")))))
    (is (= 404 (:status (default :post (str "/api/members/" id "/-/stamp_subject")
                                 nil (headers-for "root"
                                                  {"x-waymark-roles" "recovery-admin"}))))
        "the household's recovery lever is not a door onto identity either")
    (is (nil? (get-in (member-row id) [:data :subject]))
        "and nothing was written by either attempt")))

(deftest the-gate-does-not-heal-an-invited-row
  ;; an :invited row's subject belongs to the BINDING, which spends a
  ;; token; a heal there would weld an invitation to a principal that
  ;; never presented one
  (let [mid (invite! "Heal Invitee" "tok-heal-inv-01" nil)]
    (is (= 200 (:status (default :get "/api/meals" nil (headers-for mid))))
        "a principal whose id IS the invited row's resolves BY ID")
    (let [row (member-row mid)]
      (is (= :invited (:state row)) "still invited")
      (is (nil? (get-in row [:data :subject])) "still unbound")
      (is (empty? (filter #(= :stamp_subject (:action %))
                          (member-transitions mid)))))))

(deftest a-row-resolved-by-subject-is-untouched
  ;; a bound row already answers the deliverability question, and its
  ;; subject is the PRINCIPAL's id, not the row's — a heal that ran here
  ;; would overwrite a real binding with the row's uuid
  (let [mid (invite! "Bound Bea" "tok-heal-bound-01" nil)]
    (is (= 200 (:status (default :get "/api/meals" nil
                                 (headers-for "bea-oidc-sub"
                                              {"x-waymark-invite" "tok-heal-bound-01"})))))
    (let [before (count (member-transitions mid))]
      (is (= 200 (:status (default :get "/api/meals" nil
                                   (headers-for "bea-oidc-sub")))))
      (is (= "bea-oidc-sub" (get-in (member-row mid) [:data :subject]))
          "the binding stands; the row id never overwrote it")
      (is (= before (count (member-transitions mid)))))))

(deftest a-phantom-row-is-never-healed
  ;; an engine uuid no principal answers to: gate! resolves to it by
  ;; neither id nor subject, so the heal never sees it. It stays
  ;; unaddressable at the letters door, which is the truth about it —
  ;; nobody is there to open the mail.
  (let [phantom (legacy-row! (str (random-uuid)) "Phantom Roster Row")]
    (is (= 200 (:status (default :get "/api/meals" nil
                                 (headers-for "someone-else-entirely")))))
    (is (nil? (get-in (member-row phantom) [:data :subject])))
    (is (= 1 (count (member-transitions phantom)))
        "its create, and nothing since")))

(deftest a-suspended-row-waits-for-its-reinstate
  ;; the decision, recorded: a suspended row is NOT healed. It is
  ;; refused 403 one line after the gate resolves it, so the write
  ;; would buy nothing on a request that goes nowhere — and the
  ;; reinstate is followed by a sign-in, which heals it then.
  (let [id (legacy-row! "legacy-suspended" "Legacy Suspended")]
    (is (= 200 (:status (default :post (str "/api/members/" id "/-/suspend")
                                 nil admin))))
    (let [resp (default :get "/api/meals" nil (headers-for id))]
      (is (= 403 (:status resp)))
      (is (str/ends-with? (str (:type (json resp))) "member-suspended")))
    (is (nil? (get-in (member-row id) [:data :subject]))
        "refused, and unwritten")
    (is (= 200 (:status (default :post (str "/api/members/" id "/-/reinstate")
                                 nil admin))))
    (is (= 200 (:status (default :get "/api/meals" nil (headers-for id)))))
    (is (= id (get-in (member-row id) [:data :subject]))
        "the next sight after the reinstate heals it")))

(deftest a-failed-stamp-never-refuses-the-request
  ;; the heal is REPAIR, not authentication: whatever goes wrong in it,
  ;; the request proceeds exactly as it does today, with one *err* line
  (let [id (legacy-row! "heal-throws" "Heal Throws")
        err (java.io.StringWriter.)]
    (binding [*err* err]
      (with-redefs [inv/invoke! (fn [& _] (throw (ex-info "the store said no" {})))]
        (is (= 200 (:status (default :get "/api/meals" nil (headers-for id))))
            "the stamp threw; the request still authenticated")))
    (is (str/includes? (str err) id) "and warned, naming the row")
    (is (nil? (get-in (member-row id) [:data :subject]))
        "nothing was written")
    (testing "the next request heals it — an unhealed row is not a stuck one"
      (is (= 200 (:status (default :get "/api/meals" nil (headers-for id)))))
      (is (= id (get-in (member-row id) [:data :subject]))))))
