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
  (testing "[purge-terminal]: a purge that does not end the row"
    (is (= :purge-terminal
           (refused-by (-> patient-decl
                           (assoc :terminal #{})
                           (assoc-in [:actions :back]
                                     {:from #{:purged} :to :active
                                      :safety {:idempotent true :reversible true
                                               :confirm false}})))))))
