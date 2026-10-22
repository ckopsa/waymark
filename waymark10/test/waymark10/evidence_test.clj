(ns waymark10.evidence-test
  "docs/spec-documents.md §3, build step 2: :evidence. A door names the
  documents it waits for; the engine derives <field>-is-filed as a tree
  that hashes as its hand-written twin does, and its refusal says which
  document is missing in one of three sentences."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.declare :as d]
            [waymark10.evidence :as evidence]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "The prior value is on the audit trail."})

(r/defhandler ev-file [row inp _ctx]
  (update row :data merge inp))

(defn- file-door [state field form]
  {:from #{state} :to state
   :input [:map [field form]]
   :edit {:prefill [field] :fence false
          :unfenced-reason "A test door; nobody else files these."}
   :safety quiet
   :handler ev-file})

(def ^:private job
  {:kind :job
   :plural "jobs"
   :states [:open :done]
   :initial :open
   :terminal #{:done}
   :summary "{data.site} · {state}"
   :schema [:map
            [:site [:string {:min 1 :max 80}]]
            [:photos {:optional true :waymark/document {:accept ["image/*"] :min 2}}
             [:vector :string]]]
   :actions {:add_photos (file-door :open :photos [:vector :string])
             :complete {:from #{:open} :to :done :safety quiet
                        :evidence {:photos {}}}}})

(def ^:private patient
  {:kind :patient
   :plural "patients"
   :states [:active :gone]
   :initial :active
   :terminal #{:gone}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            [:consent_form {:optional true :waymark/document {:accept ["application/pdf"]}}
             :string]]
   :actions {:file_consent (file-door :active :consent_form :string)
             :leave {:from #{:active} :to :gone :safety quiet}}})

(def ^:private note
  {:kind :note
   :plural "notes"
   :states [:draft :signed]
   :initial :draft
   :terminal #{:signed}
   :summary "{data.body} · {state}"
   :schema [:map
            [:body [:string {:min 1 :max 400}]]
            [:patient_id {:kind :patient} :waymark/ref]]
   :actions {:sign {:from #{:draft} :to :signed :safety quiet
                    :evidence {:consent_form {:via :patient_id}}}}})

(defn- load-quietly [m]
  (binding [*err* (java.io.StringWriter.)]
    (r/resource m)))

(def ^:private kinds
  (evidence/derive-kinds {:job (load-quietly job)
                          :patient (load-quietly patient)
                          :note (load-quietly note)}))

(def ^:private rows
  {:attachment {"p1" {:id "p1" :state :stored}
                "p2" {:id "p2" :state :pending}
                "p3" {:id "p3" :state :stored}
                "c1" {:id "c1" :state :stored}
                "c2" {:id "c2" :state :pending}}
   :patient {"ana" {:id "ana" :state :active :data {:name "Ana Ruiz"}}
             "bea" {:id "bea" :state :active :data {:name "Bea Cole" :consent_form "c2"}}
             "cy" {:id "cy" :state :active :data {:name "Cy Park" :consent_form "c1"}}}})

(def ^:private ctx
  {:read (fn [kind id] (get-in rows [kind (str id)]))})

(defn- guard-on [kind door gname]
  (some #(when (= gname (:name %)) %) (get-in kinds [kind :actions door :guards])))

(defn- judge [gd data]
  (first (g/evaluate gd {:id "row-1" :data data} nil ctx)))

(deftest a-door-waits-for-its-documents
  (let [photos (guard-on :job :complete :photos-is-filed)
        consent (guard-on :note :sign :consent-form-is-filed)]
    (testing "the guards are named from the field and ride the door that names it"
      (is (some? photos))
      (is (some? consent))
      (is (= kinds (evidence/derive-kinds kinds)) "derived again: no second guard"))
    (testing "the remedy is the field's own edit door, the field named"
      (is (= [{:door :job/add_photos :fields [:photos]}] (:remedies photos)))
      (is (= [{:door :patient/file_consent :fields [:consent_form] :id '(data :patient_id)}]
             (:remedies consent))))
    (testing "too few: the count it needs and the count it has"
      (let [v (judge photos {:site "The Hendersons' patio" :photos ["p1"]})]
        (is (t/deny? v))
        (is (= "This job needs 2 photos to be completed; it has 1."
               (get-in v [:vars :missing])))))
    (testing "enough, one still uploading"
      (let [v (judge photos {:site "The Hendersons' patio" :photos ["p1" "p2"]})]
        (is (t/deny? v))
        (is (= "The job cannot be completed: its photos are still uploading."
               (get-in v [:vars :missing])))))
    (testing "enough, every one stored"
      (is (t/allow? (judge photos {:site "The Hendersons' patio" :photos ["p1" "p3"]}))))
    (testing "through :via, the other row's document, named by its owner"
      (let [v (judge consent {:body "Session 4" :patient_id "ana"})]
        (is (t/deny? v))
        (is (= "The note cannot be signed: Ana Ruiz's consent form is not on file."
               (get-in v [:vars :missing]))))
      (let [v (judge consent {:body "Session 4" :patient_id "bea"})]
        (is (t/deny? v))
        (is (= "The note cannot be signed: Bea Cole's consent form is still uploading."
               (get-in v [:vars :missing]))))
      (is (t/allow? (judge consent {:body "Session 4" :patient_id "cy"}))))))

(deftest a-hand-written-twin-hashes-the-same
  (let [twin (load-quietly
              (assoc-in job [:actions :complete]
                        {:from #{:open} :to :done :safety quiet
                         :guards
                         [(d/sentence-guard
                           :photos-is-filed (d/refuse "{missing}")
                           '(and (>= (count (data :photos)) 2)
                                 (every [a (data :photos)] (= "stored" (follow :attachment a :state))))
                           {:vars {:missing
                                   '(cond
                                      (< (count (data :photos)) 2)
                                      (str "This job needs 2 photos to be completed; it has "
                                           (count (data :photos)) ".")
                                      (some [a (data :photos)] (= (follow :attachment a :state) "pending"))
                                      (str "The job cannot be completed: its photos are still uploading.")
                                      :else
                                      (str "The job cannot be completed: its photos are not all on file."))}
                            :remedies [{:door :job/add_photos :fields [:photos]}]})]}))]
    (is (= (fp/fingerprint-hash (fp/fingerprint-of twin))
           (fp/fingerprint-hash (fp/fingerprint-of (:job kinds)))))))

(deftest evidence-is-judged-at-assembly
  (testing "a field that is no document"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\[evidence\]"
                          (evidence/derive-kinds
                           {:job (load-quietly (assoc-in job [:actions :complete :evidence] {:site {}}))}))))
  (testing "a :via that is no ref"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\[evidence\]"
                          (evidence/derive-kinds
                           {:patient (load-quietly patient)
                            :note (load-quietly (assoc-in note [:actions :sign :evidence]
                                                          {:consent_form {:via :body}}))})))))

(deftest a-door-that-speaks-of-a-document-is-asked-to-wait-for-it
  (let [said (fn [m] (filter #(str/starts-with? % "[evidence-in-prose]")
                             (:waymark10/warnings (meta (load-quietly m)))))
        spoken (assoc-in job [:actions :complete]
                         {:from #{:open} :to :done :safety quiet
                          :display {:label "Complete"
                                    :description "Take two photos of the finished patio first."}})]
    (is (= [(str "[evidence-in-prose] warning: job.complete speaks of \"photos\" and names no :evidence."
                 " Declare the document field and name it in :evidence."
                 " Ask: Must complete wait for the photos?")]
           (said spoken)))
    (testing "a door that names its :evidence is not asked"
      (is (empty? (said (assoc-in spoken [:actions :complete :evidence] {:photos {}})))))))
