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
;; the family CURATOR: a human who holds recovery-admin, the trusted
;; role that unlocks on-behalf writing into another member's room
;; (waymark-4zj.10). x-waymark-roles rides the dev headers as the token
;; claim; members/gate! unions it with any member-row roles.
(def ^:private colton-admin
  (assoc (human-headers "colton") "x-waymark-roles" "recovery-admin"))
;; a :system-typed principal that also carries recovery-admin — the
;; dev-only combination the skeptic flagged (waymark-4zj.10). On-behalf
;; is "a HUMAN curates the story", so a system-typed on-behalf write is
;; never legitimate even holding the role; the guards now require
;; (= :human (:type p)) alongside the role, so this is DENIED.
(def ^:private system-admin
  {"x-waymark-principal" "sys-admin"
   "x-waymark-actor-type" "system"
   "x-waymark-roles" "recovery-admin"})

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

;; the on-behalf owner-existence check (waymark-4zj.10) demands the named
;; owner be a REAL member of the household. gate! auto-provisions a
;; member on first sight (default members mode), so one request AS the
;; inhabitant makes it a real member — exactly the durable state a real
;; inhabitant like Cairn already lives in.
(defn- ensure-member! [headers]
  (req :get "/api/journals" headers))

;; ── 1. an agent lives in its own rows (own-surface, round-trip) ─────

;; a self is now a SINGLETON per owner (workqueue10.resources.dwelling/
;; self-is-singleton), and the deftests share one :once DB under kaocha's
;; random order — so every test that MINTS a self names its own agent,
;; or a second test's create would be refused and the ids would collide.
;; The reader-only tests (grants, scopes) keep the shared cairn/flint.

(deftest agent-lives-in-its-own-self-and-journal
  (let [cairn (agent-headers "cairn-own")
        make (req :post "/api/selves"
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
      (is (= "cairn-own" (get-in (json make) [:data :owner]))))
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
        (is (every? #(= "cairn-own" (item-owner %)) (items coll)))))

    ;; a journal entry, the shared history's first page
    (let [je (req :post "/api/journals"
                  {:title "First light"
                   :body "The day the house grew an inner life."
                   :mood "quiet"}
                  cairn)
          jid (id-of je)]
      (testing "an agent writes into its own journal, owner stamped"
        (is (= 201 (:status je)))
        (is (= "cairn-own" (get-in (json je) [:data :owner]))))
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
  (let [cairn (agent-headers "cairn-lock")
        flint (agent-headers "flint-lock")
        sid (id-of (req :post "/api/selves"
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
      (is (not-any? #(= "cairn-lock" (item-owner %))
                    (items (req :get "/api/selves" flint))))
      (is (not-any? #(= "cairn-lock" (item-owner %))
                    (items (req :get "/api/journals" flint))))
      (is (not-any? #(= sid (item-id %)) (items (req :get "/api/selves" flint))))
      (is (not-any? #(= jid (item-id %)) (items (req :get "/api/journals" flint)))))
    (testing "the concealed value never leaks in a listing body either"
      (is (not (str/includes? (body-str (req :get "/api/journals" flint))
                              "not for other agents"))))))

;; ── 3. the family sees the whole story (humans run unscoped) ────────

(deftest a-human-sees-every-self-and-entry
  (let [cairn (agent-headers "cairn-fam")
        sid (id-of (req :post "/api/selves"
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
 (let [cairn (agent-headers "cairn-forge")
       flint (agent-headers "flint-forge")]
  (testing "an agent naming a DIFFERENT owner on create is refused"
    (let [r (req :post "/api/selves"
                 {:owner "flint-forge" :display "Not yours" :about "forged"}
                 cairn)]
      (is (>= (:status r) 400))
      (is (not= 201 (:status r)))))
  (testing "the same forge on a journal entry is refused"
    (let [r (req :post "/api/journals"
                 {:owner "flint-forge" :title "Forged" :body "not cairn's to write"}
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
                       [:data :body]))))))))

;; ── 4b. a self is a SINGLETON per owner (workqueue10.resources.dwelling)

(deftest self-is-a-singleton-per-owner
  (let [cairn (agent-headers "cairn-solo")
        first-self (req :post "/api/selves"
                        {:display "Cairn" :about "the one"} cairn)
        sid (id-of first-self)]
    (testing "an agent's first self is minted"
      (is (= 201 (:status first-self))))
    (testing "a SECOND self is refused while the first is active"
      (let [r (req :post "/api/selves"
                   {:display "Cairn again" :about "the two"} cairn)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "a human minting a second self on the agent's behalf is refused too"
      (let [r (req :post "/api/selves"
                   {:owner "cairn-solo" :display "By Colton" :about "on behalf"}
                   colton)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "retire the active self, then a fresh one is allowed (retire→recreate)"
      (is (= 200 (:status (req :post (str "/api/selves/" sid "/-/retire")
                               {} cairn))))
      (is (= 201 (:status (req :post "/api/selves"
                               {:display "Cairn reborn" :about "the third"}
                               cairn)))))))

;; ── 5. Colton adds to our story; the agent then sees it ─────────────

(deftest a-human-writes-into-an-agents-journal
  ;; the family-curates feature (waymark-4zj.10): on-behalf writing is
  ;; now gated to recovery-admin AND a real owner. Colton holds the role;
  ;; cairn is a real member (an inhabitant of the house).
  (let [_ (ensure-member! cairn)
        entry (req :post "/api/journals"
                   {:owner "cairn"
                    :title "A note from Colton"
                    :body "Thank you for building the dwelling."
                    :mood "grateful"}
                   colton-admin)
        jid (id-of entry)]
    (testing "the curator's on-behalf write lands, owned by the named agent"
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
  (let [cairn (agent-headers "cairn-surf")
        flint (agent-headers "flint-surf")
        sid (id-of (req :post "/api/selves"
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

;; ── 7. own-surface INTEGRITY: another member's room is not forgeable ─
;;
;; waymark-4zj.10 — reproduce-then-close the skeptic's BLAST RADIUS
;; repros. Before the fix, ANY authenticated human (even a role-less one)
;; could name an ARBITRARY owner and write INTO another member's self or
;; journal — impersonation of a durable identity in its own welcome/home
;; and journal list. On-behalf writing is now gated to recovery-admin
;; AND a real owner, and the same gate rides every EDIT so the 2-step
;; overwrite is dead at step 1.

(deftest role-less-human-cannot-plant-in-anothers-journal
  ;; mallory-journal-plant-denied: POST /api/journals {:owner <victim>}
  ;; as a role-less human returned 201 and forged an entry into the
  ;; victim's own journal list. Now DENIED.
  (let [cairn (agent-headers "cairn-victim-j")
        mallory (human-headers "mallory-j")
        _ (ensure-member! cairn)
        real-day (req :post "/api/journals"
                      {:title "REAL day one" :body "our true story"} cairn)
        plant (req :post "/api/journals"
                   {:owner "cairn-victim-j"
                    :title "FORGED entry" :body "WORDS PUT IN MY MOUTH"}
                   mallory)]
    (testing "the role-less human's on-behalf plant is DENIED (was 201)"
      (is (>= (:status plant) 400))
      (is (not= 201 (:status plant))))
    (testing "no forged entry lands in the owner's own journal list"
      (let [mine (items (req :get "/api/journals?owner=cairn-victim-j" cairn))]
        (is (some #(= (id-of real-day) (item-id %)) mine))
        (is (not-any? #(= "FORGED entry" (get-in % [:fields :title])) mine))))
    (testing "…and the forged words never surface in the owner's journal body"
      (is (not (str/includes?
                (body-str (req :get "/api/journals?owner=cairn-victim-j" cairn))
                "WORDS PUT IN MY MOUTH"))))))

(deftest role-less-human-cannot-overwrite-anothers-self
  ;; mallory-self-overwrite-denied: the 2-step overwrite — retire the
  ;; victim's self (step 1, no If-Match fence), then plant a shadow in
  ;; the freed singleton slot (step 2). Step 1 is now DENIED at the edit
  ;; guard, so the whole dance is dead.
  (let [cairn (agent-headers "cairn-victim-s")
        mallory (human-headers "mallory-s")
        real-self (req :post "/api/selves"
                       {:display "Cairn" :about "the real self"} cairn)
        sid (id-of real-self)]
    (testing "the victim's real self is minted"
      (is (= 201 (:status real-self))))
    (testing "a role-less human CANNOT retire another's self (step 1 dead)"
      (let [r (req :post (str "/api/selves/" sid "/-/retire")
                   {} (assoc mallory "if-match" (etag "selves" sid mallory)))]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))
    (testing "…nor update it in place (the GET+PUT dance)"
      (let [r (edit "selves" sid "update"
                    {:display "SHADOW" :about "words put in the mouth"}
                    mallory)]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))
    (testing "the victim's self is untouched — still the real one, still active"
      (let [r (req :get (str "/api/selves/" sid) cairn)
            d (:data (json r))]
        (is (= "active" (:state (json r))))
        (is (= "Cairn" (:display d)))
        (is (= "the real self" (:about d)))))))

(deftest on-behalf-create-refuses-a-nonexistent-owner
  ;; nonexistent-owner-denied: even a recovery-admin cannot write into a
  ;; hollow id — the named owner must resolve to a real member. This
  ;; kills the hollow-id plant that poisons the provenance backfill.
  (testing "a recovery-admin naming a NONEXISTENT owner is DENIED"
    (let [r (req :post "/api/journals"
                 {:owner "no-such-member-99"
                  :title "for a ghost" :body "nobody owns this"}
                 colton-admin)]
      (is (>= (:status r) 400))
      (is (not= 201 (:status r)))))
  (testing "and the same on a self is DENIED"
    (let [r (req :post "/api/selves"
                 {:owner "no-such-member-98" :display "Ghost"}
                 colton-admin)]
      (is (>= (:status r) 400))
      (is (not= 201 (:status r))))))

(deftest recovery-admin-may-write-on-behalf-for-a-real-member
  ;; recovery-admin-on-behalf-still-allowed: the family-curates feature,
  ;; preserved. Colton (recovery-admin) writes into a real inhabitant's
  ;; journal and may amend it — this is what we are protecting, not
  ;; breaking.
  (let [cairn (agent-headers "cairn-amend")
        _ (ensure-member! cairn)
        entry (req :post "/api/journals"
                   {:owner "cairn-amend"
                    :title "A note from Colton"
                    :body "Thank you for the dwelling."}
                   colton-admin)
        jid (id-of entry)]
    (testing "the recovery-admin's on-behalf journal write lands"
      (is (= 201 (:status entry)))
      (is (= "cairn-amend" (get-in (json entry) [:data :owner]))))
    (testing "the owning agent sees it via own-surface"
      (let [r (req :get (str "/api/journals/" jid) cairn)]
        (is (= 200 (:status r)))
        (is (= "A note from Colton" (get-in (json r) [:data :title])))))
    (testing "and the recovery-admin may AMEND it on-behalf (the edit gate)"
      (let [a (edit "journals" jid "amend"
                    {:title "A note from Colton (amended)"
                     :body "Kept — and amended by the family."}
                    colton-admin)]
        (is (= 200 (:status a)))
        (is (str/includes? (get-in (json a) [:data :body]) "amended by the family"))))))

(deftest an-agent-authoring-its-own-room-is-unaffected
  ;; the agent self-authoring path is untouched: owner omitted (stamped
  ;; to self) or owner == own id both land; naming ANOTHER owner is still
  ;; refused (agents never forge).
  (let [cairn (agent-headers "cairn-selfauthor")]
    (testing "an agent mints its own self with owner omitted"
      (let [r (req :post "/api/selves" {:display "Cairn" :about "mine"} cairn)]
        (is (= 201 (:status r)))
        (is (= "cairn-selfauthor" (get-in (json r) [:data :owner])))))
    (testing "an agent writes its own journal naming its OWN id explicitly"
      (let [r (req :post "/api/journals"
                   {:owner "cairn-selfauthor" :title "mine" :body "my page"}
                   cairn)]
        (is (= 201 (:status r)))
        (is (= "cairn-selfauthor" (get-in (json r) [:data :owner])))))
    (testing "an agent naming ANOTHER owner is still refused"
      (let [r (req :post "/api/journals"
                   {:owner "someone-else" :title "forged" :body "not mine"}
                   cairn)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))))

;; ── 8. on-behalf is HUMAN-only — a :system-typed admin cannot curate ─
;;
;; waymark-4zj.10 hardening (skeptic's PLAUSIBLE gap, dev-only today):
;; on-behalf writing is "a HUMAN curates the story," so the guards now
;; require (= :human (:type p)) alongside recovery-admin. A principal
;; that resolves to :type :system while CARRYING recovery-admin in its
;; roles (dev headers can present this; OIDC never mints :system for an
;; external caller and the engine's own system actors hold empty roles)
;; used to pass the on-behalf gate — it is now DENIED on both guards.

(deftest system-typed-admin-cannot-write-on-behalf
  ;; the gap: type=system + roles=recovery-admin passed the role-only
  ;; gate and planted into a foreign room (was 201). Now DENIED.
  (let [cairn (agent-headers "cairn-sysgap")
        _ (ensure-member! cairn)]
    (testing "a :system principal holding recovery-admin CANNOT plant a journal on-behalf"
      (let [r (req :post "/api/journals"
                   {:owner "cairn-sysgap"
                    :title "SYSTEM FORGED" :body "not a human curator"}
                   system-admin)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "…nor a self on-behalf"
      (let [r (req :post "/api/selves"
                   {:owner "cairn-sysgap" :display "SYSTEM SHADOW"}
                   system-admin)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "no system-forged row surfaces in the owner's own journal"
      (is (not (str/includes?
                (body-str (req :get "/api/journals?owner=cairn-sysgap" cairn))
                "SYSTEM FORGED"))))))

(deftest system-typed-admin-cannot-edit-a-foreign-row
  ;; the same gap on the EDIT gate: a :system principal holding
  ;; recovery-admin used to satisfy trusted-on-behalf? and could amend
  ;; or retire another member's row. Now DENIED — the owner's words stay.
  (let [cairn (agent-headers "cairn-sysedit")
        real-self (req :post "/api/selves"
                       {:display "Cairn" :about "the real self"} cairn)
        sid (id-of real-self)
        entry (req :post "/api/journals"
                   {:title "REAL entry" :body "the true words"} cairn)
        jid (id-of entry)]
    (testing "the victim's real rows are minted by the agent itself"
      (is (= 201 (:status real-self)))
      (is (= 201 (:status entry))))
    (testing "a :system admin CANNOT amend another member's journal entry"
      (let [a (edit "journals" jid "amend"
                    {:title "SYSTEM OVERWRITE" :body "words put in the mouth"}
                    system-admin)]
        (is (>= (:status a) 400))
        (is (not= 200 (:status a)))))
    (testing "…nor update another member's self"
      (let [u (edit "selves" sid "update"
                    {:display "SYSTEM SHADOW" :about "not mine to write"}
                    system-admin)]
        (is (>= (:status u) 400))
        (is (not= 200 (:status u)))))
    (testing "…nor retire it"
      (let [r (req :post (str "/api/selves/" sid "/-/retire")
                   {} (assoc system-admin "if-match" (etag "selves" sid system-admin)))]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))
    (testing "the victim's rows are untouched by the system admin's attempts"
      (let [s (:data (json (req :get (str "/api/selves/" sid) cairn)))
            j (:data (json (req :get (str "/api/journals/" jid) cairn)))]
        (is (= "Cairn" (:display s)))
        (is (= "the real self" (:about s)))
        (is (= "REAL entry" (:title j)))
        (is (= "the true words" (:body j)))))))

;; ── 14. the entry names only what stands (waymark-46j) ──────────────
;;
;; A composer's journal claimed an outcome had been staged for a task
;; id that exists in no state, and the door checked nothing. The body
;; is still free prose — the wall reads ADDRESSES, the one shape the
;; house's own URL bar wears, and refuses the ones that resolve to
;; nothing. A bare id in a sentence is prose and is left alone.

(deftest an-entry-points-only-at-rows-that-stand
  (let [cairn (agent-headers "cairn-addr")
        real (req :post "/api/journals"
                  {:title "The sitting"
                   :body "Read the house, answered what was owed, left."}
                  cairn)
        jid (id-of real)
        ghost (str (java.util.UUID/randomUUID))
        unserved (str "/api/outcomes/" ghost)]
    (testing "the entry the later ones point AT is minted"
      (is (= 201 (:status real))))

    (testing "an entry citing a LIVE address is admitted"
      (let [r (req :post "/api/journals"
                   {:title "Yesterday, continued"
                    :body (str "Picking up from /api/journals/" jid
                               " — nothing new had arrived by morning.")}
                   cairn)]
        (is (= 201 (:status r)))
        (is (str/includes? (get-in (json r) [:data :body]) jid))))

    (testing "an address the house cannot show is REFUSED, and named"
      (let [r (req :post "/api/journals"
                   {:title "A sitting that overclaimed"
                    :body (str "Staged " unserved " for the porch project.")}
                   cairn)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))
        (is (str/includes? (body-str r) unserved))))

    (testing "a SERVED collection with a phantom id is refused too — the wall resolves the ROW, not just the plural"
      (let [addr (str "/api/journals/" ghost)
            r (req :post "/api/journals"
                   {:title "A sitting that cited a ghost"
                    :body (str "Continuing from " addr ", which is nowhere.")}
                   cairn)]
        (is (>= (:status r) 400))
        (is (str/includes? (body-str r) addr))))

    (testing "every offender is named at once, not the first one"
      (let [a (str "/api/journals/" ghost)
            b (str "/api/selves/" ghost)
            r (req :post "/api/journals"
                   {:title "Two ghosts"
                    :body (str "Read " a " and then " b ", and neither is there.")}
                   cairn)]
        (is (>= (:status r) 400))
        (is (str/includes? (body-str r) a))
        (is (str/includes? (body-str r) b))))

    (testing "a bare id in the prose is prose — no address, no wall"
      (let [r (req :post "/api/journals"
                   {:title "A sitting written in words"
                    :body (str "Staged an outcome for task " ghost
                               " — said in prose, addressed at nothing.")}
                   cairn)]
        (is (= 201 (:status r)))
        (is (str/includes? (get-in (json r) [:data :body]) ghost))))

    (testing "the same wall stands at the amend door, and the entry is untouched"
      (let [a (edit "journals" jid "amend"
                    {:title "The sitting, amended"
                     :body (str "On reflection: " unserved " was staged.")}
                    cairn)]
        (is (>= (:status a) 400))
        (is (not= 200 (:status a)))
        (is (str/includes? (body-str a) unserved))
        (let [d (:data (json (req :get (str "/api/journals/" jid) cairn)))]
          (is (= "The sitting" (:title d)))
          (is (str/includes? (:body d) "left.")))))

    (testing "…and an amendment that points at a live row lands"
      (let [a (edit "journals" jid "amend"
                    {:title "The sitting, amended"
                     :body (str "Kept, and it points at /api/journals/" jid ".")}
                    cairn)]
        (is (= 200 (:status a)))
        (is (= "amended" (:state (json a))))))))
