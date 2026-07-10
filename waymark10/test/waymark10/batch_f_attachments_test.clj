(ns waymark10.batch-f-attachments-test
  "Batch F, deliverable 4: attachment completeness. The byte PUT
  records the content's sha256 (stored in data, visible in the
  envelope); duplicate content natural-replays by the sha (200) while
  different content refuses (409) even at the same size; and the
  purge sweep removes :deleted attachments' bytes — directly and
  under the coherence-elected role. Real Postgres
  (WAYMARK10_TEST_DSN)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.attachments :as attachments]
            [waymark10.server.coherence :as coherence]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

(def ^:private attachment-dir "target/test-attachments-batch-f")

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["attachments" "members" "roles" "grants"
                           "subscriptions" "jobs" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [dir (io/file attachment-dir)]
          (when (.isDirectory dir)
            (doseq [^java.io.File f* (.listFiles dir)] (.delete f*))))
        (let [eng (engine/engine {:storage st :resources []
                                  :attachment-dir attachment-dir})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (*h* (cond-> {:request-method method
                 :uri uri
                 :headers {"x-waymark-principal" "colton"}}
          body (assoc :body (if (string? body) body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(defn- attachment! [nm]
  (id-of (req :post "/api/attachments" {:name nm :media_type "text/plain"})))

(defn- put! [aid body] (req :put (str "/api/attachments/" aid "/bytes") body))
(defn- file-of ^java.io.File [aid] (io/file attachment-dir aid))

;; ── 1. sha256 recorded and exposed; duplicates detected by it ───────

(deftest sha256-and-duplicate-detection
  (let [aid (attachment! "notes.txt")
        content "brisket 4200g · 225F to 203F internal"
        sha (attachments/sha256-of (.getBytes content "UTF-8"))
        put1 (put! aid content)]
    (testing "the PUT stamps size AND sha256; the envelope exposes both"
      (is (= 200 (:status put1)))
      (is (= sha (get-in (json put1) [:data :sha256])))
      (let [env (json (req :get (str "/api/attachments/" aid)))]
        (is (= "stored" (:state env)))
        (is (= sha (get-in env [:data :sha256])))
        (is (= (count (.getBytes content "UTF-8"))
               (get-in env [:data :size])))))
    (testing "byte-identical content natural-replays (duplicate detected)"
      (let [put2 (put! aid content)]
        (is (= 200 (:status put2)))
        (is (= sha (get-in (json put2) [:data :sha256])))
        (let [ts (store/with-tx (:storage *eng*)
                   #(store/transitions (:storage *eng*) %
                                       {:kind :attachment :resource-id aid} {}))]
          (is (= 1 (count (filter (fn [t] (= :mark_stored (:action t))) ts)))
              "one stored mark — the replay re-executed nothing"))))
    (testing "different content refuses 409 — even at the same size"
      (let [same-size (str/join (reverse content))
            resp (put! aid same-size)]
        (is (= (count content) (count same-size)))
        (is (= 409 (:status resp)))
        (is (= sha (get-in (json (req :get (str "/api/attachments/" aid)))
                           [:data :sha256]))
            "the stored content is untouched")))))

;; ── 2. the purge sweep ──────────────────────────────────────────────

(deftest purge-sweep-removes-deleted-bytes
  (let [aid (attachment! "purge-me.txt")
        keep-id (attachment! "keep-me.txt")]
    (put! aid "purge these bytes")
    (put! keep-id "keep these bytes")
    (is (.isFile (file-of aid)))
    (testing "nothing purges while the rows are stored"
      (is (= 0 (attachments/purge-deleted! *eng*)))
      (is (.isFile (file-of aid))))
    (req :post (str "/api/attachments/" aid "/-/delete"))
    (testing "the sweep removes exactly the deleted row's bytes"
      (is (= 1 (attachments/purge-deleted! *eng*)))
      (is (not (.exists (file-of aid))))
      (is (.isFile (file-of keep-id)) "stored rows keep their bytes")
      (is (= 0 (attachments/purge-deleted! *eng*)) "idempotent re-run"))
    (testing "the metadata row stays — the audited record"
      (let [env (json (req :get (str "/api/attachments/" aid)))]
        (is (= "deleted" (:state env)))
        (is (some? (get-in env [:data :sha256])))))))

(deftest purge-sweeper-is-an-elected-role
  (let [aid (attachment! "role-purge.txt")]
    (put! aid "bytes for the elected sweeper")
    (req :post (str "/api/attachments/" aid "/-/delete"))
    (is (.isFile (file-of aid)))
    (let [role (attachments/start-purge-sweeper! *eng* {:interval-ms 100
                                                        :retry-ms 100})]
      (try
        (let [deadline (+ (System/currentTimeMillis) 10000)]
          (loop []
            (when (and (.exists (file-of aid))
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 50)
              (recur))))
        (is (not (.exists (file-of aid)))
            "the elected sweeper purged within a few intervals")
        (is (true? @(:held? role)))
        (finally (coherence/stop-role! role))))))
