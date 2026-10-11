(ns waymark10.domains-test
  "The domain (epic aff24e84, piece 1): a person makes and restates one
  and an agent's call is held, retire is refused while a seat names the
  domain, the boot seed makes `factory` once, a seat with no domain
  reads as being in factory, and a seat restate that leaves `domain`
  out keeps the stored value.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN overrides
  the DSN."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.holds :as holds]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:dynamic *eng* nil)

(def ^:private tables
  ["domains" "seats" "models" "sittings" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_drafts"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*eng* (engine/engine {:storage st :resources []})]
          (f))
        (finally (pg/close! st))))))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk (t/principal {:id "clerk" :type :agent :display "Clerk"}))

(defn- row-of [kind id]
  (let [rdef (get (inv/resources *eng*) kind)]
    (some->> (store/with-tx (:storage *eng*)
               (fn [tx]
                 (store/load-row (:storage *eng*) tx kind (str id) {})))
             (inv/decode-row rdef))))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(defn- domain-body [name' extra]
  (merge {:name name'
          :charter "Keep the house running."
          :budget_usd_per_week 40M}
         extra))

(defn- make-domain!
  ([name'] (make-domain! name' {}))
  ([name' extra]
   (:row (inv/create! *eng* :domain (domain-body name' extra)
                      {:principal colton}))))

(defn- act! [kind id action body principal]
  (let [row (row-of kind id)]
    (inv/invoke! *eng* kind (str id) action body
                 {:principal principal
                  :if-match (inv/etag kind (str id) (:version row))})))

(defn- seat-restate-body [extra]
  (merge {:charter "Decide whether a message asks something of this house."
          :scope [{:kind "model" :actions ["retire"]}]
          :standing_ttl_seconds 604800
          :cadence_seconds 3600
          :budget_usd_per_week 5M
          :sitting_budget_tokens 60000}
         extra))

(defn- open-seat!
  ([name'] (open-seat! name' {}))
  ([name' extra]
   (:row (inv/create! *eng* :seat (seat-restate-body (assoc extra :name name'))
                      {:principal colton}))))

(defn- state-of [row] (name (:state row)))

(deftest a-person-makes-a-domain-and-an-agents-create-is-held
  (let [made (make-domain! "create-door")]
    (is (= "active" (state-of made)))
    (is (= "create-door" (get-in made [:data :name])))
    (is (nil? (get-in made [:data :owner])) "the owner is optional")
    (is (nil? (get-in made [:data :mayor])) "and so is the mayor"))
  (testing "an agent's create is a hold, not a write"
    (let [d (refusal #(inv/create! *eng* :domain (domain-body "create-agent" {})
                                   {:principal clerk}))]
      (is (some? d))
      (is (holds/hold? (:guard d)))
      (is (nil? (domains/domain-named *eng* "create-agent")))))
  (testing "a second domain of the same name is refused"
    (is (thrown? Exception (make-domain! "create-door")))))

(deftest a-restate-is-a-patch-and-an-agents-is-held
  (let [id (:id (make-domain! "restate-door"))]
    (act! :domain id :restate {:budget_usd_per_week 90M} colton)
    (let [row (row-of :domain id)]
      (is (zero? (compare 90M (get-in row [:data :budget_usd_per_week]))))
      (is (= "Keep the house running." (get-in row [:data :charter]))
          "a field the restate leaves out keeps its stored value"))
    (testing "an agent's restate is a hold, and the row does not move"
      (let [d (refusal #(act! :domain id :restate {:budget_usd_per_week 1M} clerk))]
        (is (some? d))
        (is (holds/hold? (:guard d)))
        (is (zero? (compare 90M (get-in (row-of :domain id)
                                        [:data :budget_usd_per_week]))))))))

;; tickets and changes name a domain by its name (ticket 20fab5f9)
(deftest a-restate-never-renames-a-domain
  (let [id (:id (make-domain! "rename-door"))]
    (is (thrown? Exception
                 (act! :domain id :restate {:name "renamed"} colton)))
    (is (= "rename-door" (get-in (row-of :domain id) [:data :name])))))

(deftest retire-is-refused-while-a-seat-names-the-domain
  (let [named (:id (make-domain! "retire-named"))
        free (:id (make-domain! "retire-free"))]
    (open-seat! "retire-seat" {:domain (str named)})
    (let [d (refusal #(act! :domain named :retire {} colton))]
      (is (some? d))
      (is (= "no-seat-names-the-domain" (name (:guard d))))
      (is (= "active" (state-of (row-of :domain named)))))
    (act! :domain free :retire {} colton)
    (is (= "retired" (state-of (row-of :domain free))))))

(deftest the-boot-seeds-factory-once
  (let [mayor (:id (open-seat! "mayor"))]
    (domains/ensure-factory! *eng*)
    (domains/ensure-factory! *eng*)
    (let [row (domains/domain-named *eng* seats/default-domain)
          factory (row-of :domain (:id row))]
      (is (some? row))
      (is (= (str mayor) (str (get-in factory [:data :mayor]))))
      (is (zero? (compare 3600M (get-in factory [:data :budget_usd_per_week]))))
      (is (nil? (get-in factory [:data :owner])))
      (testing "and factory is never retired: seats that name no domain are in it"
        (is (some? (refusal #(act! :domain (:id row) :retire {} colton))))))))

(deftest a-seat-with-no-domain-reads-as-factory
  (let [seat (open-seat! "default-seat")]
    (is (nil? (get-in (row-of :seat (:id seat)) [:data :domain]))
        "nothing is stored")
    (is (= "factory" (seats/domain-name-of row-of (row-of :seat (:id seat)))))))

(deftest a-seat-restate-keeps-the-stored-domain
  (let [household (:id (make-domain! "household"))
        seat (:id (open-seat! "restated-seat" {:domain (str household)}))]
    (is (= "household" (seats/domain-name-of row-of (row-of :seat seat))))
    (testing "a restate that leaves domain out keeps it"
      (act! :seat seat :restate (seat-restate-body {}) colton)
      (is (= (str household) (str (get-in (row-of :seat seat) [:data :domain])))))
    (testing "a restate that clears it puts the seat back in factory"
      (act! :seat seat :restate (seat-restate-body {:domain nil}) colton)
      (is (= "factory" (seats/domain-name-of row-of (row-of :seat seat)))))))

(defn- born
  "What a kind's birth hook makes of `data` for `principal`, reading the
  engine's rows."
  [resource data principal]
  ((:on-create resource)
   {:data data}
   (assoc (t/ctx {:principal principal :now (java.time.Instant/now)})
          :read row-of)))

(deftest a-sitting-carries-its-seats-domain
  (let [household (:id (make-domain! "sitting-house"))
        named (:id (open-seat! "sitting-named" {:domain (str household)}))
        bare (:id (open-seat! "sitting-bare"))
        sat (fn [seat]
              (:data (born seats/sitting
                           {:seat (str seat) :model "m" :grant "g"} clerk)))]
    (is (= "sitting-house" (:domain (sat named)))
        "the seat's stored domain, by name")
    (is (not (contains? (sat bare) :domain))
        "a seat with no stored domain stamps nothing")))

(deftest an-approval-request-carries-its-requesters-seats-domain
  (let [household (:id (make-domain! "ask-house"))
        named (open-seat! "ask-named" {:domain (str household)})
        bare (open-seat! "ask-bare")
        sitter (fn [seat]
                 (t/principal {:id (seats/sitter-id seat) :type :agent}))
        asked (fn [principal]
                (:data (born grants/approval-request
                             {:task "read the pantry" :domain "claimed"}
                             principal)))]
    (is (= "ask-house" (:domain (asked (sitter named))))
        "the requester's seat's stored domain, by name")
    (is (not (contains? (asked (sitter bare)) :domain))
        "a seat with no stored domain stamps nothing")
    (is (not (contains? (asked clerk) :domain))
        "a member with no seat gets nothing")))
