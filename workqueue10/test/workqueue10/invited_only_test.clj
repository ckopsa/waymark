(ns workqueue10.invited-only-test
  "The production engine admits only invited members (ticket
  c157a094): booted with `main/membership`, an unknown human sign-in
  is refused 403 and no member row is written; a known member and a
  delegate of an active member pass; an invited row binds on first
  sign-in with its token.

  Needs a Postgres database; WAYMARK10_TEST_DSN names it.
  Run: cd workqueue10 && clojure -M:test --focus workqueue10.invited-only-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]
            [workqueue10.main :as main]))

(defn- empty-database!
  "Every table in the schema, dropped: the boot starts from nothing."
  [st]
  (store/with-tx st
    (fn [tx]
      (doseq [{:keys [tablename]}
              (jdbc/execute! tx ["SELECT tablename FROM pg_tables WHERE schemaname = current_schema()"]
                             {:builder-fn rs/as-unqualified-lower-maps})]
        (jdbc/execute! tx [(str "DROP TABLE IF EXISTS \"" tablename "\" CASCADE")])))))

(def ^:private admin
  ;; system principals bypass the gate: the operator's own actor
  {"x-waymark-principal" "admin" "x-waymark-actor-type" "system"})

(defn- human [id & [extra]]
  (merge {"x-waymark-principal" id "x-waymark-actor-type" "human"} extra))

(defn- req [h method uri & [body headers]]
  (h (cond-> {:request-method method :uri uri :headers (or headers {})}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp]
  (some-> (:body resp) (#(if (string? %) % (slurp %))) wire/read-json))

(deftest the-production-engine-admits-only-invited-members
  (let [st (pg/storage db/dsn)]
    (try
      (empty-database! st)
      (let [eng (engine/engine {:storage st
                                :resources (main/factory-only-resources)
                                :members main/membership
                                :auto-migrate true
                                :suppress-mirror-refresh true})
            h (engine/handler eng)]
        (is (= :invited-only (:members eng))
            "the mode survives boot")
        (testing "an unknown human sign-in is refused, and no member row is written"
          (let [resp (req h :get "/api/.well-known/waymark" nil (human "stranger"))]
            (is (= 403 (:status resp)))
            (is (re-find #"membership-invited" (str (:type (json resp))))))
          (is (= 404 (:status (req h :get "/api/members/stranger" nil admin)))))
        (testing "a known member passes"
          (inv/create! eng :member
                       {:display "Colton" :actor_type "human" :subject "colton"}
                       {:principal grants/approvals-actor :id "colton"})
          (is (= 200 (:status (req h :get "/api/.well-known/waymark" nil (human "colton"))))))
        (testing "a delegate of an active member is provisioned; a stranger's is refused"
          (is (some? (members/gate! eng (assoc (t/principal {:id "connector:colton"
                                                             :type :agent
                                                             :roles #{}})
                                               :acts-for "colton"))))
          (is (thrown? Exception
                       (members/gate! eng (assoc (t/principal {:id "connector:stranger"
                                                               :type :agent
                                                               :roles #{}})
                                                 :acts-for "stranger")))))
        (testing "an invited row binds on first sign-in with its token"
          (let [created (req h :post "/api/members"
                             {:display "Priya" :actor_type "human"
                              :bind_token "tok-priya-c157"}
                             admin)
                mid (last (str/split (:self (json created)) #"/"))]
            (is (= 201 (:status created)))
            (is (= "invited" (:state (json created))))
            (is (= 200 (:status (req h :get "/api/.well-known/waymark" nil
                                     (human "priya-google-sub"
                                            {"x-waymark-invite" "tok-priya-c157"})))))
            (let [env (json (req h :get (str "/api/members/" mid) nil admin))]
              (is (= "active" (:state env)))
              (is (= "priya-google-sub" (get-in env [:data :subject])))))))
      (finally (pg/close! st)))))
