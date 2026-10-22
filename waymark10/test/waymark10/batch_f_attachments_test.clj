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
            [waymark10.machine :as machine]
            [waymark10.resource :as r]
            [waymark10.server.attachments :as attachments]
            [waymark10.server.coherence :as coherence]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.law-sweep :as law-sweep]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.worksheet :as worksheet]
            [waymark10.server.xlsx :as xlsx]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(def ^:private attachment-dir "target/test-attachments-batch-f")

;; a kind with document fields (docs/spec-documents.md §2); the cover's
;; :accept is an argument so a test can tighten it as a later law would
(defn- site-visit-decl [cover-accept]
  {:kind :site_visit
    :plural "site_visits"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]
             [:photos {:optional true
                       :waymark/document {:accept ["image/*"] :min 2 :personal false}
                       :x-display {:label "Photos"}}
              [:maybe [:vector :string]]]
             [:cover {:optional true
                      :waymark/document {:accept cover-accept :personal false}
                      :x-display {:label "Cover"}}
              [:maybe :string]]]
    :worksheet {:columns [{:field :title :create-only true}
                          {:field :cover :create-only true}]
                :create true}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}}})

(def ^:private site-visit (r/resource (site-visit-decl ["image/*"])))

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["attachments" "members" "roles" "grants"
                           "subscriptions" "jobs" "definitions" "site_visits"
                           "worksheets"
                           "waymark10_transitions" "waymark10_idempotency"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [dir (io/file attachment-dir)]
          (when (.isDirectory dir)
            (doseq [^java.io.File f* (.listFiles dir)] (.delete f*))))
        (let [eng (engine/engine {:storage st :resources [site-visit]
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
        (let [rdef attachments/attachment
              roads (machine/roads rdef (get-in rdef [:actions :mark_stored])
                                   :stored)
              env (json (req :get (str "/api/attachments/" aid)))]
          (is (= roads (vec (:remedies (json resp))))
              "the 409 names the machine's roads, and no others")
          (is (= [] roads (vec (:remedies (get-in env [:unavailable :mark_stored]))))
              "which is the envelope's answer too: the door is hidden,
               and nothing leads back to pending"))
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
    ;; bind the engine HERE: start-fn runs on the election thread,
    ;; which carries no dynamic bindings from this one.
    ;; The election is the module's, declared on its lifecycle hook
    ;; (:elected :attachments-purge) since waymark-db9.4 —
    ;; start-purge-sweeper! is the plain loop now
    (let [eng *eng*
          role (coherence/start-role!
                (:storage eng) :attachments-purge
                {:retry-ms 100
                 :start-fn #(attachments/start-purge-sweeper!
                             eng {:interval-ms 100})
                 :stop-fn attachments/stop-purge-sweeper!})]
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

;; ── 3. a document field (docs/spec-documents.md §2) ──────────────────

(defn- media! [nm media-type]
  (id-of (req :post "/api/attachments" {:name nm :media_type media-type})))

(defn- log-of [aid]
  (store/with-tx (:storage *eng*)
    #(store/transitions (:storage *eng*) %
                        {:kind :attachment :resource-id aid} {})))

(deftest a-document-field-refuses-an-unaccepted-type
  (let [notes (media! "notes.txt" "text/plain")
        resp (req :post "/api/site_visits" {:title "Henderson patio"
                                             :photos [notes]})
        why (str (:errors (json resp)))]
    (is (= 422 (:status resp)))
    (is (str/includes? why "photos") "the refusal names the field")
    (is (str/includes? why "notes.txt") "…the file")
    (is (str/includes? why "image/*") "…and the types it takes")))

(deftest a-document-field-shows-who-added-each-file
  (let [patio (media! "patio.jpg" "image/jpeg")
        _ (put! patio "not really a jpeg")
        resp (req :post "/api/site_visits" {:title "Henderson patio"
                                             :photos [patio]})
        _ (is (< (:status resp) 300) (str (:body resp)))
        env (json (req :get (str "/api/site_visits/" (id-of resp))))
        [entry] (get-in env [:documents :photos])
        log (log-of patio)
        stored (first (filter #(= :mark_stored (:action %)) log))]
    (is (= [patio] (get-in env [:data :photos])) "the row stores the ref alone")
    (is (= patio (:id entry)))
    (is (= (get-in (first log) [:actor :id]) (:added_by entry))
        "who added it is the attachment's create, read from its log")
    (is (not= (:id attachments/bytes-actor) (:added_by entry))
        "…not the bytes route that stored it")
    (is (= (str (:at stored)) (:added_at entry))
        "when is its mark_stored")))

;; ── 4. the type is judged at every write, and a tightened law says so ─

(def ^:private colton (t/principal {:id "colton" :type :human :display "Colton"}))

(deftest an-import-writing-an-unaccepted-type-is-refused
  (let [notes (media! "notes.txt" "text/plain")
        rdef (get (inv/resources *eng*) :site_visit)
        ws (:row (worksheet/stage! *eng* rdef
                                   (xlsx/write-sheet [["id" "title" "cover"]
                                                      [nil "Imported patio" notes]])
                                   {:principal colton}))
        applied (:row (inv/invoke! *eng* :worksheet (:id ws) :apply nil
                                   {:principal colton
                                    :idempotency-key (str (random-uuid))}))
        [line] (get-in applied [:data :report])]
    (is (= "refused" (:outcome line))
        (str "the import's create runs the same :accept check: " (pr-str line)))))

(deftest the-law-sweep-lists-rows-a-tightened-accept-leaves-behind
  (let [patio (media! "patio.jpg" "image/jpeg")
        kept (id-of (req :post "/api/site_visits" {:title "Covered" :cover patio}))
        bare (id-of (req :post "/api/site_visits" {:title "No cover"}))
        tightened (r/resource (site-visit-decl ["image/png"]))
        st (:storage *eng*)
        rows (store/with-tx st
               #(store/query-rows st % :site_visit {} {:limit 500}))
        found (law-sweep/document-type-findings
               *eng* tightened (mapv #(inv/decode-row tightened %) rows))
        ids (set (map (comp str :id) found))]
    (is (contains? ids kept) "the row filed under image/* is named")
    (is (not (contains? ids bare)) "a row with no cover is not")
    (is (every? #(= :document_type_drift (:class %)) found))
    (is (str/includes? (str (:detail (first found))) "patio.jpg")
        "the detail names the file and its type")
    (is (empty? (law-sweep/document-type-findings
                 *eng* site-visit (mapv #(inv/decode-row site-visit %) rows)))
        "under the law it was filed in, nothing drifts")))
