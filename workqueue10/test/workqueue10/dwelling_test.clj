(ns workqueue10.dwelling-test
  "The dwelling kinds (waymark-4zj.1): an agent's :self profile and the
  shared :journal, lived in over the REAL ring handler so the whole
  privacy model runs — wrap-identity's agent-default-deny and
  human-unscoped, and the own-surface addition in
  waymark10.server.grants that lets an agent see and edit its OWN rows
  without a grant.

  The adversarial heart is cross-agent isolation: a DIFFERENT agent,
  no grant, must get NOTHING for the first agent's self and journal —
  private by construction. Assertions are order-independent (kaocha
  randomizes, and the deftests share one DB): every test names its own
  rows and never asserts on collection SIZE or a foreign agent's
  emptiness, only on ownership. Needs the waymark10_test database;
  WAYMARK10_TEST_DSN overrides.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.dwelling-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [workqueue10.resources.dwelling :refer [self journal]]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

(def ^:private tables
  ["selves" "journals" "members" "roles" "grants" "approval_requests"
   "attachments" "subscriptions" "jobs" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st :resources [self journal]})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (batch_b_access_test's idiom) ─────────────────────

(defn- agent-headers [id]
  {"x-waymark-principal" id "x-waymark-actor-type" "agent"})

(defn- human-headers [id]
  {"x-waymark-principal" id "x-waymark-actor-type" "human"})

;; the inhabitants and the family
(def ^:private cairn (agent-headers "cairn"))
(def ^:private flint (agent-headers "flint"))
(def ^:private colton (human-headers "colton"))

(defn- req
  ([method uri headers] (req method uri nil headers))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path :headers (or headers {})}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp)
                           (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- body-str ^String [resp]
  (let [b (:body resp)] (if (string? b) b (slurp b))))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

;; a single-row GET renders data under :data; collection items under
;; :fields — the two envelope shapes the router draws
(defn- items [resp] (get-in (json resp) [:data :items]))
(defn- item-owner [it] (get-in it [:fields :owner]))
(defn- item-id [it] (last (str/split (:self it) #"/")))

;; the edit fence: :edit actions demand If-Match, so an edit reads the
;; live etag first and presents it — the ordinary optimistic-lock dance
(defn- etag [kind id headers]
  (get-in (json (req :get (str "/api/" kind "/" id) headers)) [:meta :etag]))

(defn- edit [kind id action body headers]
  (req :post (str "/api/" kind "/" id "/-/" action) body
       (assoc headers "if-match" (etag kind id headers))))

;; ── 1. an agent lives in its own rows (own-surface, round-trip) ─────

(deftest agent-lives-in-its-own-self-and-journal
  (let [make (req :post "/api/selves"
                  {:display "Cairn"
                   :pronouns "it/its"
                   :about "A steady hand at the house's inner work."
                   :boundaries "I do not act on money without a person."
                   :lessons "Verify against framework source, never stale punts."
                   :working_notes "Mid-flight: the dwelling kinds."}
                  cairn)
        sid (id-of make)]
    (testing "the create lands, owner stamped to the creating agent"
      (is (= 201 (:status make)))
      (is (= "cairn" (get-in (json make) [:data :owner]))))
    (testing "the agent reads its own self back — prose bodies round-trip"
      (let [r (req :get (str "/api/selves/" sid) cairn)
            d (:data (json r))]
        (is (= 200 (:status r)))
        (is (= "Cairn" (:display d)))
        (is (= "it/its" (:pronouns d)))
        (is (= "A steady hand at the house's inner work." (:about d)))
        (is (= "I do not act on money without a person." (:boundaries d)))
        (is (str/includes? (:lessons d) "stale punts"))
        (is (str/includes? (:working_notes d) "dwelling"))))
    (testing "the agent edits its own profile — the edit round-trips"
      (let [e (edit "selves" sid "update"
                    {:display "Cairn" :pronouns "it/its"
                     :about "A steady hand — now with the journal shipped."
                     :boundaries "I do not act on money without a person."
                     :lessons "Verify against framework source."
                     :working_notes "Shipped: self + journal, private by construction."}
                    cairn)]
        (is (= 200 (:status e)))
        (is (str/includes? (get-in (json e) [:data :about]) "journal shipped"))))
    (testing "the agent's own self appears in its own collection, owner-scoped"
      (let [coll (req :get "/api/selves" cairn)]
        (is (= 200 (:status coll)))
        (is (some #(= sid (item-id %)) (items coll)))
        (is (every? #(= "cairn" (item-owner %)) (items coll)))))

    ;; a journal entry, the shared history's first page
    (let [je (req :post "/api/journals"
                  {:title "First light"
                   :body "The day the house grew an inner life."
                   :mood "quiet"}
                  cairn)
          jid (id-of je)]
      (testing "an agent writes into its own journal, owner stamped"
        (is (= 201 (:status je)))
        (is (= "cairn" (get-in (json je) [:data :owner]))))
      (testing "it reads the entry back, body prose intact"
        (let [r (req :get (str "/api/journals/" jid) cairn)]
          (is (= 200 (:status r)))
          (is (= "First light" (get-in (json r) [:data :title])))
          (is (= "The day the house grew an inner life."
                 (get-in (json r) [:data :body])))
          (is (= "quiet" (get-in (json r) [:data :mood])))))
      (testing "the agent amends its own entry (fenced), title round-trips"
        (let [a (edit "journals" jid "amend"
                      {:title "First light (amended)"
                       :body "The day the house grew an inner life — kept."
                       :mood "settled"}
                      cairn)]
          (is (= 200 (:status a)))
          (is (= "amended" (:state (json a))))
          (is (= "First light (amended)" (get-in (json a) [:data :title]))))))))

;; ── 2. THE KEY TEST: a different agent sees NOTHING ─────────────────

(deftest a-foreign-agent-is-locked-out-by-construction
  (let [sid (id-of (req :post "/api/selves"
                        {:display "Cairn" :about "private thoughts"} cairn))
        jid (id-of (req :post "/api/journals"
                        {:title "Private page" :body "not for other agents"}
                        cairn))]
    (testing "a DIFFERENT agent, no grant, 404s the self — private, not 403"
      (let [r (req :get (str "/api/selves/" sid) flint)]
        (is (= 404 (:status r)))
        (is (not (str/includes? (body-str r) "private thoughts")))))
    (testing "…and 404s the journal entry the same way"
      (let [r (req :get (str "/api/journals/" jid) flint)]
        (is (= 404 (:status r)))
        (is (not (str/includes? (body-str r) "not for other agents")))))
    (testing "the foreign agent's collections surface NONE of cairn's rows"
      (is (not-any? #(= "cairn" (item-owner %))
                    (items (req :get "/api/selves" flint))))
      (is (not-any? #(= "cairn" (item-owner %))
                    (items (req :get "/api/journals" flint))))
      (is (not-any? #(= sid (item-id %)) (items (req :get "/api/selves" flint))))
      (is (not-any? #(= jid (item-id %)) (items (req :get "/api/journals" flint)))))
    (testing "the concealed value never leaks in a listing body either"
      (is (not (str/includes? (body-str (req :get "/api/journals" flint))
                              "not for other agents"))))))

;; ── 3. the family sees the whole story (humans run unscoped) ────────

(deftest a-human-sees-every-self-and-entry
  (let [sid (id-of (req :post "/api/selves"
                        {:display "Cairn" :about "the house's inner work"}
                        cairn))
        jid (id-of (req :post "/api/journals"
                        {:title "For the family" :body "our shared record"}
                        cairn))]
    (testing "a human reads an agent's self — no grant, no own-surface"
      (let [r (req :get (str "/api/selves/" sid) colton)]
        (is (= 200 (:status r)))
        (is (= "the house's inner work" (get-in (json r) [:data :about])))))
    (testing "and reads the agent's journal entry"
      (let [r (req :get (str "/api/journals/" jid) colton)]
        (is (= 200 (:status r)))
        (is (= "our shared record" (get-in (json r) [:data :body])))))
    (testing "the human's collections DO include the agent's rows (family sees all)"
      (is (some #(= sid (item-id %)) (items (req :get "/api/selves" colton))))
      (is (some #(= jid (item-id %)) (items (req :get "/api/journals" colton)))))))

;; ── 4. an agent cannot forge ownership, nor touch another's rows ────

(deftest an-agent-cannot-mint-or-edit-what-it-does-not-own
  (testing "an agent naming a DIFFERENT owner on create is refused"
    (let [r (req :post "/api/selves"
                 {:owner "flint" :display "Not yours" :about "forged"}
                 cairn)]
      (is (>= (:status r) 400))
      (is (not= 201 (:status r)))))
  (testing "the same forge on a journal entry is refused"
    (let [r (req :post "/api/journals"
                 {:owner "flint" :title "Forged" :body "not cairn's to write"}
                 cairn)]
      (is (>= (:status r) 400))
      (is (not= 201 (:status r)))))
  (testing "no forged flint-row was minted by cairn — flint's view has neither"
    (is (not-any? #(= "Not yours" (get-in % [:fields :display]))
                  (items (req :get "/api/selves" flint))))
    (is (not-any? #(= "Forged" (get-in % [:fields :title]))
                  (items (req :get "/api/journals" flint)))))
  (testing "an agent cannot even ADDRESS another agent's row to edit it"
    ;; flint owns rows; cairn tries to update/amend them — concealed 404,
    ;; the edit never reaches the guard because the row is invisible. The
    ;; If-Match is real so a 404 is concealment, not a fence miss.
    (let [fsid (id-of (req :post "/api/selves" {:display "Flint"} flint))
          fjid (id-of (req :post "/api/journals"
                           {:title "Flint's page" :body "flint's words"} flint))
          fs-etag (etag "selves" fsid flint)
          fj-etag (etag "journals" fjid flint)]
      (is (= 404 (:status (req :post (str "/api/selves/" fsid "/-/update")
                               {:display "hijacked"}
                               (assoc cairn "if-match" fs-etag)))))
      (is (= 404 (:status (req :post (str "/api/journals/" fjid "/-/amend")
                               {:title "hijacked" :body "hijacked"}
                               (assoc cairn "if-match" fj-etag)))))
      (testing "flint's own rows are untouched by the attempt"
        (is (= "Flint" (get-in (json (req :get (str "/api/selves/" fsid) flint))
                               [:data :display])))
        (is (= "flint's words"
               (get-in (json (req :get (str "/api/journals/" fjid) flint))
                       [:data :body])))))))

;; ── 5. Colton adds to our story; the agent then sees it ─────────────

(deftest a-human-writes-into-an-agents-journal
  (let [entry (req :post "/api/journals"
                   {:owner "cairn"
                    :title "A note from Colton"
                    :body "Thank you for building the dwelling."
                    :mood "grateful"}
                   colton)
        jid (id-of entry)]
    (testing "the human's on-behalf write lands, owned by the named agent"
      (is (= 201 (:status entry)))
      (is (= "cairn" (get-in (json entry) [:data :owner]))))
    (testing "the audit trail records COLTON as the author, not cairn"
      ;; author is the transition ACTOR, never a data field — read the log
      (let [ts (store/with-tx (:storage *eng*)
                 #(store/transitions (:storage *eng*)
                                     % {:kind :journal :resource-id jid} {}))]
        (is (= "colton" (get-in (first ts) [:actor :id])))
        (is (= :create (:action (first ts))))))
    (testing "the OWNING agent now sees Colton's entry via own-surface"
      (let [r (req :get (str "/api/journals/" jid) cairn)]
        (is (= 200 (:status r)))
        (is (= "A note from Colton" (get-in (json r) [:data :title])))
        (is (str/includes? (get-in (json r) [:data :body]) "dwelling"))))
    (testing "a foreign agent still cannot see it — ownership, not authorship"
      (is (= 404 (:status (req :get (str "/api/journals/" jid) flint)))))))

;; ── 6. self and journal are NEVER grantable — the privacy footgun ────
;;
;; The adversarial hole (waymark-4zj.1): a grant (or an ask, which
;; MINTS a grant on approval) whose scope named self/journal would
;; bypass own-surface — row?/ids-of would consult the grant surface,
;; which carries no owner filter, and expose EVERY agent's private
;; rows. The framework's scope validator (scope-omits-private-kinds in
;; waymark10.server.grants) refuses such a scope at BOTH doors, so the
;; privacy is structural. These asserts prove the refusal fires, that
;; an ordinary (non-private) scope still passes (regression), and that
;; own-surface — which does NOT go through grants — is untouched.

(deftest self-and-journal-cannot-be-granted
  (testing "an ask whose scope names journal is refused (>=400), no grant minted"
    (let [r (req :post "/api/approval_requests"
                 {:task "peek at the shared history"
                  :scope [{:kind "journal" :actions []}]}
                 cairn)]
      (is (>= (:status r) 400))
      (is (not= 201 (:status r)))))
  (testing "…and the same ask naming self is refused"
    (let [r (req :post "/api/approval_requests"
                 {:task "peek at another agent's profile"
                  :scope [{:kind "self" :actions []}]}
                 cairn)]
      (is (>= (:status r) 400))
      (is (not= 201 (:status r)))))
  (testing "a hand-offered grant whose scope names journal is refused"
    (let [r (req :post "/api/grants"
                 {:audience "flint"
                  :scope [{:kind "journal" :actions []}]}
                 colton)]
      (is (>= (:status r) 400))
      (is (not= 201 (:status r)))))
  (testing "…and a grant naming self is refused"
    (let [r (req :post "/api/grants"
                 {:audience "flint"
                  :scope [{:kind "self" :actions []}]}
                 colton)]
      (is (>= (:status r) 400))
      (is (not= 201 (:status r)))))
  (testing "a private kind mixed with a real one still refuses the whole scope"
    (let [r (req :post "/api/approval_requests"
                 {:task "a real kind plus a smuggled private one"
                  :scope [{:kind "member" :actions []}
                          {:kind "journal" :actions []}]}
                 cairn)]
      (is (>= (:status r) 400))
      (is (not= 201 (:status r))))))

(deftest an-ordinary-scope-not-naming-private-kinds-still-works
  (testing "an ask naming only a non-private kind is accepted (regression)"
    (let [r (req :post "/api/approval_requests"
                 {:task "read the household roster"
                  :scope [{:kind "member" :actions []}]}
                 cairn)]
      (is (= 201 (:status r)))))
  (testing "a hand-offered grant naming only a non-private kind is accepted"
    (let [r (req :post "/api/grants"
                 {:audience "flint"
                  :scope [{:kind "member" :actions []}]}
                 colton)]
      (is (= 201 (:status r))))))

(deftest own-surface-survives-the-grant-refusal
  ;; own-surface does NOT go through grants — the refusal must not touch
  ;; it. An agent still reads and lists its OWN self/journal with no grant.
  (let [sid (id-of (req :post "/api/selves"
                        {:display "Cairn" :about "still mine"} cairn))
        jid (id-of (req :post "/api/journals"
                        {:title "Still mine" :body "own-surface intact"} cairn))]
    (testing "the agent reads its own self back with no grant"
      (let [r (req :get (str "/api/selves/" sid) cairn)]
        (is (= 200 (:status r)))
        (is (= "still mine" (get-in (json r) [:data :about])))))
    (testing "and its own journal entry"
      (let [r (req :get (str "/api/journals/" jid) cairn)]
        (is (= 200 (:status r)))
        (is (= "own-surface intact" (get-in (json r) [:data :body])))))
    (testing "its own rows still appear in its own collections"
      (is (some #(= sid (item-id %)) (items (req :get "/api/selves" cairn))))
      (is (some #(= jid (item-id %)) (items (req :get "/api/journals" cairn)))))
    (testing "a foreign agent still sees none of them (private by construction)"
      (is (= 404 (:status (req :get (str "/api/selves/" sid) flint))))
      (is (= 404 (:status (req :get (str "/api/journals/" jid) flint)))))))
