(ns workqueue10.ticket-birth-test
  "A fired seat's ticket birth, end to end (ticket b0ec4d47).

  THE BIRTH READS THE SITTING, AND THE SITTING IS A ROW. The ticket's
  `:on-create` stamps priority 4 on a ticket a FIRED seat files and
  keeps what the seat asked in `asked_priority`. It knows the hand by
  one query: the open sittings under the grant the request wore, read
  through the ctx `:find` hook. factory10.ticket-test judges that hook
  over a fake `:find`; this file is the other half — a real seat, a
  real `waymark_sit`, the grant the engine minted for it, and the
  store's own answer to `{:grant gid :state :open}` on Postgres.

  It lives in this suite and not in factory10's because factory10's
  runs with no database: this is the one suite that has both the
  `ticket` kind on its classpath and a Postgres under it.

  The sit is meal_planner_seat_test's: `mcp/call-tool` with a delegate
  principal, and every call after it as the sitter the sit bound. No
  model sits and no Routine fires — the engine's answers are the proof.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [factory10.resources.ticket :refer [ticket]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-09-30T12:00:00Z"))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private engine-tables
  "The seat's own kinds are enrolled by the framework, so they are not
  in the resources below and have to be named here."
  ["seats" "models" "sittings" "grants" "schedules" "approval_requests"
   "members" "roles" "definitions" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private colton {"x-waymark-principal" "colton"})

(def ^:private person
  "The person behind the connector: the one `waymark_sit` says the
  sitter acts for."
  (t/principal {:id "colton" :display "Colton Kopsa"}))

(def ^:private delegate
  "The owner's connector. `:acts-for` rides OUTSIDE t/principal's
  closed shape, the way oidc.clj's gate assocs it — it is the one
  thing `waymark_sit` asks of the session that presents the key, and
  what an interactive seat needs behind the run."
  (assoc (t/principal {:id "connector:colton" :type :agent
                       :display "Claude for Colton"})
         :acts-for "colton"))

(def ^:private fired-key
  "128 bits and more of base64url — what a machine mints and no hand
  types."
  "dGlja2V0LWJpcnRoLWZpcmVkLWtleS0wMQ")

(def ^:private interactive-key
  "The second seat's key: one key per seat, so the sit never guesses."
  "dGlja2V0LWJpcnRoLWZpcmVkLWtleS0wMg")

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (*h* (cond-> {:request-method method :uri uri :headers colton}
          body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [env] (last (str/split (str (:self env)) #"/")))

(defn- created!
  "One create by the person, at the HTTP door. → the envelope."
  [plural body]
  (let [resp (req :post (str "/api/" plural) body)]
    (assert (= 201 (:status resp)) (str plural ": " (:body resp)))
    (json resp)))

(defn- open-seat!
  "A seat that may file tickets and nothing else, with its key offered
  the way a person offers it. NO schedule is minted: `sit` reads the
  chair out of `held_for` when there is no schedule row, and an
  interactive seat has none."
  [model-id name' mode key']
  (let [seat-id (id-of (created! "seats"
                                 {:name name'
                                  :charter "File one ticket for each gap you find."
                                  :mode mode
                                  :scope [{:kind "ticket" :actions ["create"]}]
                                  :held_for [model-id]
                                  :standing_ttl_seconds 604800
                                  :cadence_seconds 3600
                                  :budget_usd_per_week 5
                                  :sitting_budget_tokens 60000}))]
    (inv/invoke! *eng* :seat seat-id :offer_key {:key key'}
                 {:principal person})
    seat-id))

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table (cons (store/definition-checked-name (:plural ticket))
                                engine-tables)]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources [ticket]
                                  ;; main.clj's posture: the render
                                  ;; probe carries the read hooks
                                  :probe-reads true
                                  :now-fn (constantly t0)})]
          (binding [*eng* eng *h* (engine/handler eng)]
            (let [model-id (id-of (created! "models"
                                            {:name "claude-filer-5"
                                             :display "Filer 5"
                                             :vendor "anthropic" :tier "strong"
                                             :price_input_per_mtok 3
                                             :price_output_per_mtok 15
                                             :price_cache_read_per_mtok 0.3
                                             :price_cache_write_per_mtok 3.75}))]
              ;; BOTH offices, here rather than in a deftest: the two
              ;; deftests share this database, and each must find the
              ;; other's seat standing whatever order they run in — a
              ;; fired sitting open under ONE grant is exactly what the
              ;; interactive seat's birth must not read as its own.
              (open-seat! model-id "ticket-filer" "fired" fired-key)
              (open-seat! model-id "ticket-desk" "interactive" interactive-key)
              (f))))
        (finally (pg/close! st))))))

;; ── the sit, and the sitter's calls ─────────────────────────────────

(defn- sit!
  "One `waymark_sit` with `key'`, through the real tool body, in the
  session `sid`. The session is the delegate the connector resolves
  to; everything about the sitter — its member row, its grant, its
  sitting — the engine makes itself."
  [sid key']
  (let [r (mcp/call-tool *eng* (mcp/door *eng*)
                         {:principal delegate :mcp-session-id sid}
                         "waymark_sit" {:key key'})
        text (str (get-in r [:content 0 :text]))]
    (is (false? (:isError r)) text)
    (wire/read-json text)))

(defn- sitter-session
  "The session a BOUND MCP session runs as, built the way the transport
  builds it for every call after the bind (routes/mcp.clj's
  `sitter-session`): the sitter's principal off the binding
  `waymark_sit` wrote, and the sitter's own visibility — the worn seat
  grant, which is the grant the birth reads the sittings by."
  [sid]
  (let [sitter (:sitter (:bound (mcp/touch-session! *eng* sid)))]
    (is (some? sitter) "waymark_sit bound this session to the seat's sitter")
    {:principal sitter
     :mcp-session-id sid
     :visibility (or (grants/worn-visibility *eng* sitter)
                     (grants/bootstrap-visibility *eng* sitter))}))

(defn- files-a-ticket!
  "The sitter's create at priority 1, under the seat's own leash. → the
  tool answer, with its parsed document under :doc."
  [sid title]
  (let [r (mcp/call-tool *eng* (mcp/door *eng*) (sitter-session sid)
                         "waymark_invoke"
                         {:kind "ticket" :action "create"
                          :input {:title title
                                  :detail "Found beside the work, and not fixed there."
                                  :type "task"
                                  :priority 1
                                  :repo "ckopsa/waymark"}})
        text (str (get-in r [:content 0 :text]))]
    (assoc r :text text
           :doc (try (wire/read-json text) (catch Exception _ nil)))))

(defn- as-the-person-reads-it
  "The stored ticket, read back at the HTTP door by a person — whose
  sight no grant narrows."
  [id]
  (:data (json (req :get (str "/api/tickets/" id)))))

(defn- open-sittings-under
  "The birth hook's own question, asked of the store with the hook's
  own where and opts (ticket.clj, `filed-by-a-fired-seat?`)."
  [grant-id]
  (let [st (:storage *eng*)]
    (store/with-tx st
      (fn [tx]
        (store/query-rows st tx :sitting
                          {:grant (str grant-id) :state :open}
                          {:limit 50 :newest-first true})))))

;; ── 1 · a fired seat's create at 1 lands at 4 ───────────────────────

(deftest a-fired-seats-ticket-is-born-at-four-and-keeps-what-it-asked
  (let [sid (mcp/open-session! *eng*)
        answer (sit! sid fired-key)]

    (testing "the sit opened a fired sitting under the seat's grant"
      (is (= "fired" (str (:mode answer))))
      (is (some? (:grant answer))))

    (testing "the store answers that sitting to the birth's own query"
      (is (= [(str (:sitting answer))]
             (mapv #(str (:id %)) (open-sittings-under (:grant answer))))
          "one open sitting under this grant, and it is the one the sit
           made: `grant` and `state` both narrow the page"))

    (testing "the create at 1 is stored at 4, beside the 1 it asked"
      (let [r (files-a-ticket! sid "A gap the fired seat found")
            data (as-the-person-reads-it (id-of (:doc r)))]
        (is (false? (:isError r)) (:text r))
        (is (= "draft" (str (get-in r [:doc :state]))))
        (is (= 4 (:priority data))
            "the birth stamped the back of the queue over what was asked")
        (is (= 1 (:asked_priority data))
            "and kept the seat's own judgment for the groomer")))))

;; ── 2 · an interactive seat's create lands as asked ─────────────────

(deftest an-interactive-seats-ticket-is-born-as-asked
  (let [sid (mcp/open-session! *eng*)
        answer (sit! sid interactive-key)]

    (testing "the sit opened an interactive sitting under a grant of its own"
      (is (= "interactive" (str (:mode answer))))
      (is (= [(str (:sitting answer))]
             (mapv #(str (:id %)) (open-sittings-under (:grant answer))))
          "the fired seat's sitting is under another grant, and the
           where on `grant` keeps it off this page"))

    (testing "the create at 1 is stored at 1, and nothing was asked apart"
      (let [r (files-a-ticket! sid "A gap a person found from the desk")
            data (as-the-person-reads-it (id-of (:doc r)))]
        (is (false? (:isError r)) (:text r))
        (is (= 1 (:priority data))
            "a person is in the chair, so the birth leaves the ask alone")
        (is (nil? (:asked_priority data)))))))
