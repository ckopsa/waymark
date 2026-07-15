(ns waymark10.link-where-test
  "The narrowed owns embed (design §24): an owns link's :where rides
  the compiled href (state=fresh), the splice reads it back through
  the collection grammar as a LOCKED param, and the assembly refuses
  a :where the target collection would 400 — the same contract as
  :pick. Real Postgres through the real handler."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.checks-assembly :as ca]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(defn- folder-map [& {:keys [where] :or {where {:state :fresh}}}]
  {:kind :lw_folder
   :plural "lw_folders"
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 40}]]
            [:fresh_count {:optional true} [:maybe :int]]]
   :owns [{:kind :lw_doc :via :folder_id}]
   :derived {:fresh_count {:count {:owns :lw_doc
                                   :where {:state #{"fresh"}}}}}
   :links [{:rel :fresh_docs :owns :lw_doc :embed true
            :badge :fresh_count :where where
            :summary "The docs still fresh in this folder"}]
   :actions {:close {:from #{:open} :to :closed
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Closed is history."}}}})

(def ^:private doc
  (r/resource
   {:kind :lw_doc
    :plural "lw_docs"
    :states [:fresh :filed]
    :initial :fresh
    :terminal #{:filed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:folder_id {:kind :lw_folder :filter #{:eq}} :waymark/ref]]
    :actions {:file {:from #{:fresh} :to :filed
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Filed is history."}}}}))

(def ^:private tables
  ["lw_folders" "lw_docs" "definitions" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_observations"])

(def ^:private headers
  {"x-waymark-principal" "link-where" "content-type" "application/json"})

(defn- with-handler [f]
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st))))
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/handler (engine/engine {:storage st
                                         :resources [(r/resource (folder-map))
                                                     doc]})))
      (finally (pg/close! st)))))

(defn- req [h method uri & [body]]
  (let [[path query] (str/split uri #"\?" 2)]
    (h (cond-> {:request-method method :uri path :headers headers}
         query (assoc :query-string query)
         body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

;; ── 1. the narrowed embed over the wire ─────────────────────────────

(deftest where-narrows-the-compiled-href-and-the-splice
  (with-handler
    (fn [h]
      (let [folder (json (req h :post "/api/lw_folders" {:title "f"}))
            fid (last (str/split (:self folder) #"/"))
            d1 (json (req h :post "/api/lw_docs"
                          {:title "keep" :folder_id fid}))
            d2 (json (req h :post "/api/lw_docs"
                          {:title "gone" :folder_id fid}))]
        (is (= 200 (:status (req h :post (str (:self d2) "/-/file")))))
        (let [env (json (req h :get (:self folder)))]
          (testing "the href carries the declared narrowing"
            (is (= (str "/api/lw_docs?folder_id=" fid "&state=fresh")
                   (get-in env [:links :fresh_docs :href]))))
          (testing "the splice serves the narrowed truth"
            (is (= 1 (get-in env [:links :fresh_docs :total])))
            (is (= [(:self d1)]
                   (mapv :self (get-in env [:links :fresh_docs :embedded])))))
          (testing "the badge agrees — one fact, two advertisements"
            (is (= 1 (get-in env [:links :fresh_docs :badge])))))
        (testing "the narrowing is locked — an override refuses"
          (is (= 422 (:status (req h :get (str (:self folder)
                                               "?embed.fresh_docs.state=filed"))))))))))

;; ── 2. the assembly holds :where to the target ──────────────────────

(deftest assembly-holds-where-to-the-target
  (let [assemble (fn [fmap]
                   (ca/run-all {:kinds {:lw_folder (r/resource fmap)
                                        :lw_doc doc}}))]
    (testing "a declared state narrows fine"
      (is (map? (assemble (folder-map)))))
    (testing "an undeclared state refuses"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"not a state of"
           (assemble (folder-map :where {:state :haunted})))))
    (testing "an unfilterable field refuses"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"not an :eq/:in-filterable field"
           (assemble (folder-map :where {:title "x"})))))
    (testing ":where without :owns has no compiled home"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"narrows a compiled :owns href"
           (assemble (-> (folder-map)
                         (assoc :links [{:rel :self_link
                                         :href "/api/lw_folders/{id}"
                                         :where {:state :fresh}}]))))))))
