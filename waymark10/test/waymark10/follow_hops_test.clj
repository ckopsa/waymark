(ns waymark10.follow-hops-test
  "follow from a quantifier binder and through a ref's ref, and the pure
  cond (docs/spec-documents.md §3): the :photos tree and the :via
  consent-form tree bind their kinds, cross the wire, evaluate, and are
  unknown with no :read hook to ask, as on the render probe; a third hop
  is refused at the def site, a hop through a field that is not a ref at
  assembly; cond picks one of three sentences, and is unknown on an
  unknown earlier test."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.checks-assembly :as ca]
            [waymark10.expr :as e]
            [waymark10.guards :as g]
            [waymark10.registry :as registry]
            [waymark10.resource :as r]
            [waymark10.wire :as wire]))

;; ── the world ─────────────────────────────────────────────────────────

(def ^:private photos-filed
  (g/expr {:name :photos-are-filed
           :explain "{why}"
           :remedies [:fh_visit/close]
           :when '(every [a (data :photos)] (= (follow a :state) "stored"))
           :vars {:why '(cond (= (count (data :photos)) 0) "No photo is attached yet."
                              (some [a (data :photos)] (= (follow a :state) "pending"))
                              "A photo is still uploading."
                              :else "Every photo is stored.")}}))

(def ^:private consent-filed
  (g/expr {:name :consent-is-filed
           :explain "The patient's consent form is not stored yet."
           :remedies [:fh_visit/close]
           :when '(= (follow :fh_attachment (follow (data :patient_id) :consent_form) :state)
                     "stored")}))

(def ^:private one-way
  {:idempotent true :reversible false :confirm false :one-way "It is done."})

(defn- attachment-map []
  {:kind :fh_attachment
   :plural "fh_attachments"
   :states [:pending :stored]
   :initial :pending
   :terminal #{:stored}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:min 1 :max 40}]]]
   :actions {:store {:from #{:pending} :to :stored :safety one-way}}})

(defn- patient-map []
  {:kind :fh_patient
   :plural "fh_patients"
   :states [:active :gone]
   :initial :active
   :terminal #{:gone}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 40}]]
            [:consent_form {:optional true :kind :fh_attachment} [:maybe :waymark/ref]]
            [:forms {:optional true :kind :fh_attachment} [:maybe [:vector :waymark/ref]]]]
   :actions {:leave {:from #{:active} :to :gone :safety one-way}}})

(defn- visit-map [guards]
  {:kind :fh_visit
   :plural "fh_visits"
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 40}]]
            [:patient_id {:optional true :kind :fh_patient} [:maybe :waymark/ref]]
            [:photos {:optional true :kind :fh_attachment} [:maybe [:vector :waymark/ref]]]]
   :actions {:close {:from #{:open} :to :closed :guards (vec guards) :safety one-way}}})

(def ^:private world
  (delay {:fh_attachment (r/resource (attachment-map))
          :fh_patient (r/resource (patient-map))
          :fh_visit (r/resource (visit-map [photos-filed consent-filed]))}))

(defn- guard-named [n]
  (some #(when (= n (:name %)) %) (get-in (:fh_visit @world) [:actions :close :guards])))

(def ^:private rows
  {[:fh_attachment "a1"] {:id "a1" :state :stored :data {:name "front"}}
   [:fh_attachment "a2"] {:id "a2" :state :pending :data {:name "back"}}
   [:fh_patient "p1"] {:id "p1" :state :active :data {:name "Ann" :consent_form "a1"}}
   [:fh_patient "p2"] {:id "p2" :state :active :data {:name "Bo" :consent_form "a2"}}})

(defn- read-row [kind id] (get rows [kind (str id)]))

;; ── 1. a binder is a ref ───────────────────────────────────────────────

(deftest a-quantifier-binder-is-a-ref
  (let [g (guard-named :photos-are-filed)
        w (:when g)]
    (testing "the binder's kind is the ref-vector's, written into the tree"
      (is (= (e/normalize '(every (data :photos) (= (follow :fh_attachment (it 0) :state) "stored")))
             w))
      (is (= [:fh_attachment] (:reads g))))
    (testing "the bound tree crosses the wire and comes back the same law"
      (is (= w (wire/wire->form (wire/form->wire w)))))
    (testing "every photo stored is true; one still pending is false"
      (is (true? (e/evaluate w {:data {:photos ["a1"]} :read read-row})))
      (is (false? (e/evaluate w {:data {:photos ["a1" "a2"]} :read read-row}))))
    (testing "with no :read hook to ask, as on the probe, it is unknown"
      (is (e/unknown? (e/evaluate w {:data {:photos ["a1"]}}))))))

;; ── 2. two hops ────────────────────────────────────────────────────────

(deftest a-follow-reaches-through-a-ref's-ref
  (let [g (guard-named :consent-is-filed)
        w (:when g)]
    (testing "the first hop's kind is written in, the second as authored"
      (is (= (e/normalize '(= (follow :fh_attachment
                                      (follow :fh_patient (data :patient_id) :consent_form)
                                      :state)
                              "stored"))
             w))
      (is (= [:fh_attachment :fh_patient] (:reads g)) "both hops' kinds are read"))
    (testing "the tree crosses the wire"
      (is (= w (wire/wire->form (wire/form->wire w)))))
    (testing "the consent form the patient names is stored, or is not"
      (is (true? (e/evaluate w {:data {:patient_id "p1"} :read read-row})))
      (is (false? (e/evaluate w {:data {:patient_id "p2"} :read read-row}))))
    (testing "with no :read hook to ask, it is unknown"
      (is (e/unknown? (e/evaluate w {:data {:patient_id "p1"}}))))))

(deftest a-third-hop-and-a-hop-that-is-not-a-ref-are-refused
  (testing "a third hop is refused at the def site"
    (is (thrown-with-msg? Exception #"at most two hops"
                          (g/expr {:name :three-hops :explain "Too far."
                                   :remedies [:fh_visit/close]
                                   :when '(= (follow (follow (follow (data :patient_id) :consent_form)
                                                             :owner)
                                                     :state)
                                             "x")}))))
  (testing "[follow-not-a-ref]: a hop through a field that is not a ref"
    (let [bad (g/expr {:name :not-a-ref-hop :explain "The name is a row."
                       :remedies [:fh_visit/close]
                       :when '(= (follow :fh_attachment (follow (data :patient_id) :name) :state)
                                 "stored")})]
      (is (thrown-with-msg? Exception #"(?s)\[follow-not-a-ref\].*fh_patient\.name"
                            (ca/run-all {:kinds (assoc @world :fh_visit
                                                       (r/resource (visit-map [bad])))})))))
  (testing "the fixture itself assembles"
    (is (map? (ca/run-all {:kinds @world})))))

;; ── 2b. the registry writes a second hop's kind ───────────────────────

(defn- assembled [guards]
  (registry/registry [(r/resource (attachment-map))
                      (r/resource (patient-map))
                      (r/resource (visit-map guards))]))

(deftest the-registry-binds-a-second-hop-and-a-binder-over-a-follow
  (let [unwritten (g/expr {:name :consent-unwritten
                           :explain "The patient's consent form is not stored yet."
                           :remedies [:fh_visit/close]
                           :when '(= (follow (follow (data :patient_id) :consent_form) :state)
                                     "stored")})
        forms (g/expr {:name :forms-are-filed
                       :explain "A form is not stored yet."
                       :remedies [:fh_visit/close]
                       :when '(every [f (follow (data :patient_id) :forms)]
                                     (= (follow f :state) "stored"))})
        guard (fn [n] (some #(when (= n (:name %)) %)
                            (get-in (assembled [unwritten forms])
                                    [:kinds :fh_visit :actions :close :guards])))]
    (testing "the second hop's kind is the ref's, as if the author wrote it"
      (is (= (:when (guard-named :consent-is-filed)) (:when (guard :consent-unwritten))))
      (is (= [:fh_attachment :fh_patient] (:reads (guard :consent-unwritten)))))
    (testing "a binder over a follow's ids reaches the kind they name"
      (is (= (e/normalize '(every (follow :fh_patient (data :patient_id) :forms)
                                  (= (follow :fh_attachment (it 0) :state) "stored")))
             (:when (guard :forms-are-filed))))
      (is (= [:fh_attachment :fh_patient] (:reads (guard :forms-are-filed)))))
    (testing "[follow-not-a-ref]: a second hop through a field that is not a ref"
      (is (thrown-with-msg? Exception #"\[follow-not-a-ref\]"
                            (assembled [(g/expr {:name :name-hop :explain "The name is a row."
                                                 :remedies [:fh_visit/close]
                                                 :when '(= (follow (follow (data :patient_id) :name)
                                                                   :state)
                                                           "stored")})]))))))

;; ── 3. cond ───────────────────────────────────────────────────────────

(deftest cond-chooses-one-of-three-sentences
  (let [why (get-in (guard-named :photos-are-filed) [:vars :why])
        say #(e/evaluate why {:data {:photos %} :read read-row})]
    (is (= "No photo is attached yet." (say [])))
    (is (= "A photo is still uploading." (say ["a1" "a2"])))
    (is (= "Every photo is stored." (say ["a1"])))
    (testing "an unknown test before any true one leaves the choice unknown"
      (is (e/unknown? (e/evaluate why {:data {:photos ["a1"]}}))))
    (testing "a true test before the unknown one still chooses"
      (is (= "No photo is attached yet." (e/evaluate why {:data {:photos []}}))))
    (testing ":else and true are one law"
      (is (= (e/normalize '(cond (data :x) 1 :else 2))
             (e/normalize '(cond (data :x) 1 true 2)))))
    (testing "cond crosses the wire"
      (is (= why (wire/wire->form (wire/form->wire why)))))
    (testing "a test with no value is refused"
      (is (seq (e/problems '(cond (data :x) 1 :else)))))))
