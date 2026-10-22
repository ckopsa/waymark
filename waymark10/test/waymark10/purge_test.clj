(ns waymark10.purge-test
  "Documents 4a (docs/spec-documents.md §5): a :purge true door clears
  the personal fields and lets go of their files in one act, and the
  row stays as an audited skeleton. In memory, with the declaration
  checks beside it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.attachments :as attachments]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private no-agent-alone
  (g/guard {:name :no-agent-purges-alone
            :reads [:principal :within]
            :hold true
            :explain "A patient is erased by a person; an agent's purge waits for the person's tap."
            :open "No door clears this one; the call waits for the person's tap."
            :check (fn [_row _inp ctx]
                     (if (= :agent (:type (:principal ctx))) (t/deny) (t/allow)))}))

(def ^:private patient-decl
  {:kind :patient
   :states [:active :purged]
   :initial :active
   :terminal #{:purged}
   :summary "{data.name}"
   :retain {:data true}
   :schema [:map
            [:name {:personal true :x-display {:label "Name"}} [:string {:min 1 :max 80}]]
            [:phone {:optional true :personal true :x-display {:label "Phone"}}
             [:maybe [:string {:max 40}]]]
            [:email {:optional true :personal true :x-display {:label "Email"}}
             [:maybe [:string {:max 80}]]]
            [:clinic {:optional true :x-display {:label "Clinic"}} [:maybe [:string {:max 80}]]]
            [:forms {:optional true :waymark/document {} :x-display {:label "Forms"}}
             [:maybe [:vector :string]]]]
   :actions
   {:purge {:from #{:active} :to :purged :purge true
            :waives #{:required}
            :guards [no-agent-alone]
            :safety {:idempotent true :reversible false :confirm true}
            :display {:label "Erase" :style :danger :order 9}}}})

(defn- load-quietly [m]
  (binding [*err* (java.io.StringWriter.)]
    (r/resource m)))

(def ^:private patient (load-quietly patient-decl))

(def ^:private folder
  (load-quietly
   {:kind :folder
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title}"
    :schema [:map
             [:title [:string {:max 80}]]
             [:files {:optional true :waymark/document {:personal false}}
              [:maybe [:vector :string]]]]
    :actions {:close {:from #{:open} :to :closed
                      :safety {:idempotent true :reversible false :confirm false
                               :one-way "A closed folder is history."}}}}))

(def ^:dynamic *eng* nil)

(use-fixtures :each
  (fn [f]
    (binding [*eng* (inv/engine {:storage (memory/storage)
                                 :resources [attachments/attachment patient folder]})]
      (f))))

(def ^:private opts {:principal (t/principal {:id "dana" :display "Dana"})})

(defn- file! [nm]
  (str (:id (:row (inv/create! *eng* :attachment
                               {:name nm :media_type "application/pdf"} opts)))))

(defn- state-of [kind id]
  (let [st (:storage *eng*)]
    (store/with-tx st
      (fn [tx] (keyword (name (:state (store/load-row st tx kind (str id) {}))))))))

(defn- last-transition [kind id]
  (let [st (:storage *eng*)]
    (store/with-tx st
      (fn [tx] (first (store/transitions st tx {:kind kind :resource-id id}
                                         {:newest-first true :limit 1}))))))

(deftest a-purge-leaves-the-skeleton
  (let [own (file! "intake.pdf")
        shared (file! "consent.pdf")
        _ (inv/create! *eng* :folder {:title "Consents" :files [shared]} opts)
        row (:row (inv/create! *eng* :patient
                               {:name "Ana Ruiz" :phone "555-0101"
                                :email "ana@example.com" :clinic "North"
                                :forms [own shared]}
                               opts))
        pid (:id row)]
    (testing "the consequence sentence names the row and what it erases"
      (is (= "Ana Ruiz's name, phone, email and 2 documents are erased for good; the record stays."
             (render/purge-consequence patient row))))
    (let [after (:row (inv/invoke! *eng* :patient pid :purge nil opts))]
      (testing "the row holds no personal value, and keeps the rest"
        (is (= :purged (:state after)))
        (is (every? #(nil? (get-in after [:data %])) [:name :phone :email :forms]))
        (is (= "North" (get-in after [:data :clinic]))))
      (testing "its own file is deleted; a file another row holds is only let go of"
        (is (= :deleted (state-of :attachment own)))
        (is (= :pending (state-of :attachment shared))))
      (testing "the log line names the fields it cleared, never their values"
        (let [s (str (:summary (last-transition :patient pid)))]
          (is (str/includes? s "erased name, phone, email, forms") s)
          (is (str/includes? s "1 file deleted") s)
          (is (str/includes? s (str "unlinked " shared)) s)
          (is (not-any? #(str/includes? s %) ["Ana" "555-0101" "ana@example.com"]) s))))))

;; ── Documents 4b: the purge reaches the row's past log ────────────────

;; an expression guard whose evidence reads a personal field and a
;; plain one, so the redaction has a value to take and one to leave
(def ^:private named-on-file
  (g/expr {:name :named-on-file
           :when '(is-set (data :name))
           :explain "A call needs a name; {who} at {clinic} has none."
           :vars {:who '(data :phone)
                  :clinic '(data :clinic)}}))

(def ^:private client
  (load-quietly
   {:kind :client
    :plural "clients"
    :states [:active :purged]
    :initial :active
    :terminal #{:purged}
    :summary "{data.clinic}"
    :retain {:judgment true :data true}
    :schema [:map
             [:name {:personal true :x-display {:label "Name"}} [:string {:min 1 :max 80}]]
             [:phone {:optional true :personal true :x-display {:label "Phone"}}
              [:maybe [:string {:max 40}]]]
             [:clinic {:optional true :x-display {:label "Clinic"}} [:maybe [:string {:max 80}]]]]
    :actions
    {:move {:from #{:active} :to :active
            :input [:map
                    [:phone {:optional true} [:maybe [:string {:max 40}]]]
                    [:clinic {:optional true} [:maybe [:string {:max 80}]]]]
            :record true
            :guards [named-on-file]
            :handler (fn [row inp _ctx] (update row :data merge inp))
            :safety {:idempotent true :reversible true :confirm false}}
     :purge {:from #{:active} :to :purged :purge true
             :waives #{:required}
             :guards [no-agent-alone]
             :safety {:idempotent true :reversible false :confirm true}
             :display {:label "Erase" :style :danger :order 9}}}}))

(defn- each-store
  "Run `f` against a memory engine and a Postgres engine over `client`."
  [f]
  (f :memory (inv/engine {:storage (memory/storage) :resources [client]}))
  (db/with-test-engine [client] (fn [eng] (f :postgres eng))))

(defn- log-of [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx] (store/transitions st tx {:kind kind :resource-id (str id)} {})))))

(deftest a-purge-redacts-the-log
  (each-store
   (fn [where eng]
     (let [cid (:id (:row (inv/create! eng :client
                                       {:name "Ana Ruiz" :phone "555-0101" :clinic "North"}
                                       opts)))
           _ (inv/invoke! eng :client cid :move {:phone "555-0199" :clinic "South"} opts)
           _ (inv/invoke! eng :client cid :purge nil opts)
           log (log-of eng :client cid)
           move (first (filter #(= "move" (name (:action %))) log))
           guard (some #(when (= "named-on-file" (:name %)) %)
                       (get-in move [:judgment :guards]))]
       (testing (str where ": no personal value survives in the row's log")
         (let [s (pr-str log)]
           (is (not-any? #(str/includes? s %) ["Ana Ruiz" "555-0101" "555-0199"]) s)))
       (testing (str where ": old inputs read purged, the rest stand")
         (is (= store/purged-value (get-in move [:inputs :phone])))
         (is (= "South" (get-in move [:inputs :clinic]))))
       (testing (str where ": retained judgment evidence reads purged, the rest stand")
         (is (= store/purged-value (get-in guard [:read :who])))
         (is (= "North" (get-in guard [:read :clinic]))))
       (testing (str where ": the retained document reads purged, the rest stand")
         (is (= store/purged-value (get-in move [:after :name])))
         (is (= "South" (get-in move [:after :clinic]))))
       (testing (str where ": names, actors and states stay")
         (is (= ["create" "move" "purge"] (mapv #(name (:action %)) log)))
         (is (= "purged" (name (:to-state (last log))))))))))

(deftest the-sentence-counts-what-it-erases
  (is (= "Bo's name, phone and email are erased for good; the record stays."
         (render/purge-consequence patient {:state :active :data {:name "Bo"}})))
  (is (= "Bo's name, phone, email and 1 document are erased for good; the record stays."
         (render/purge-consequence patient {:state :active
                                            :data {:name "Bo" :forms ["f1"]}}))))

(defn- refused-by [decl]
  (try (load-quietly decl) nil
       (catch clojure.lang.ExceptionInfo e (:check (ex-data e)))))

(deftest a-purge-is-held-confirmed-and-final
  (testing "the declaration as written stands"
    (is (nil? (refused-by patient-decl))))
  (testing "[purge-unheld]: no hold for an agent's call"
    (is (= :purge-unheld
           (refused-by (assoc-in patient-decl [:actions :purge :guards] [])))))
  (testing "[purge-unheld]: not confirm"
    (is (= :purge-unheld
           (refused-by (assoc-in patient-decl [:actions :purge :safety]
                                 {:idempotent true :reversible false :confirm false
                                  :one-way "Erased."})))))
  (testing "[purge-required]: a required field cleared without waiving :required"
    (is (= :purge-required
           (refused-by (update-in patient-decl [:actions :purge] dissoc :waives)))))
  (testing "[purge-summary]: a summary reading a cleared field, no retained document"
    (let [e (try (load-quietly (dissoc patient-decl :retain)) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :purge-summary (:check (ex-data e))))
      (is (str/includes? (ex-message e)
                         (str "`patient`'s summary reads `name`, which its purge clears; "
                              "declare :retain {:data true} so a purge can redact its past log.")))))
  (testing "[purge-summary]: a summary reading no cleared field needs no retained document"
    (is (nil? (refused-by (-> patient-decl
                              (dissoc :retain)
                              (assoc :summary "{data.clinic} · {state}"))))))
  (testing "[purge-terminal]: a purge that does not end the row"
    (is (= :purge-terminal
           (refused-by (-> patient-decl
                           (assoc :terminal #{})
                           (assoc-in [:actions :back]
                                     {:from #{:purged} :to :active
                                      :safety {:idempotent true :reversible true
                                               :confirm false}})))))))
