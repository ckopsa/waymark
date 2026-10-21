(ns waymark10.integration-audit-test
  "§ 8's audit (docs/spec-integrations.md, build step 11): the nine
  integration findings in the declaration battery, each with its
  remedy and, where it has one, its question. Pure: declarations
  through the import-time gate."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.checks :as checks]
            [waymark10.resource :as resource :refer [defhandler]]))

(defhandler keep-the-amount [row inp _ctx]
  (assoc-in row [:data :amount_cents] (:amount_cents inp)))

(defhandler keep-the-ref [row inp _ctx]
  (assoc-in row [:data :payment_ref] (:payment_ref inp)))

(def ^:private once
  {:idempotent true :reversible false :confirm false
   :one-way "A paid order keeps its payment."})

(def ^:private paid-event
  {:source :stripe
   :type "payment_intent.succeeded"
   :match {:payment_ref '(event "data" "object" "id")}
   :door :mark_paid
   :input {:amount_cents '(event "data" "object" "amount_received")}
   :tell ["billing-seat"]})

(defn- order-law [event]
  {:kind :audit_order
   :states [:open :paid]
   :initial :open
   :terminal #{:paid}
   :summary "{data.payment_ref} · {state}"
   :schema [:map
            [:payment_ref [:string {:min 1 :max 120}]]
            [:amount_cents {:optional true} [:maybe :int]]]
   :filterable {:state #{:eq :in} :payment_ref #{:eq}}
   :actions {:mark_paid {:from #{:open} :to :paid
                         :input [:map [:amount_cents [:int {:min 1}]]]
                         :waives #{:edit-shape}
                         :handler keep-the-amount
                         :safety once}}
   :on-event [event]})

(def ^:private buy-label
  {:power "postage__create_label"
   :input {:number '(data :number)}
   :why "Buy the postage label for parcel {number}."
   :idempotency-key '(str "label:" (self))
   :writes {:label_ref '(result "object_id")}
   :lands :labelled
   :fails-to :label_failed})

(defn- parcel-law [effect & {:as more}]
  {:kind :audit_parcel
   :states [:packed :label_asked :labelled :label_failed]
   :initial :packed
   :summary "{data.number} · {state}"
   :schema [:map
            [:number [:string {:min 1 :max 40}]]
            [:label_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :actions (merge {:buy_label {:from #{:packed} :to :label_asked
                                :safety once
                                :effect effect}
                    :repack {:from #{:label_asked :labelled :label_failed} :to :packed
                             :safety once}}
                   more)})

(defn- said
  "What the import-time gate said of this law, warnings and info both."
  [law]
  (let [m (meta (resource/resource law))]
    (concat (:waymark10/warnings m) (:waymark10/info m))))

(defn- of [finding lines]
  (filter #(str/starts-with? % (str "[" (name finding) "]")) lines))

(deftest the-nine-findings-each-name-a-remedy
  (is (= 9 (count checks/integration-findings)))
  (is (every? (comp seq :remedy) (vals checks/integration-findings)))
  (is (= #{:integration-in-prose :typed-after-the-fact :effect-no-key
           :effect-no-failure-state :unwinds-no-compensate :refused-event-untold}
         (set (keep (fn [[k v]] (when (:question v) k)) checks/integration-findings)))))

(deftest a-whole-integration-says-nothing
  (let [lines (said (order-law paid-event))]
    (doseq [f (keys checks/integration-findings)]
      (is (empty? (of f lines)) (name f)))))

(deftest integration-in-prose
  (let [law (update (order-law paid-event) :schema conj
                    [:carrier_ref {:optional true} [:maybe [:string {:max 120}]]])
        [line :as lines] (of :integration-in-prose (said law))]
    (is (= 1 (count lines)) "payment_ref is the event's match; carrier_ref nothing writes")
    (is (str/starts-with? line "[integration-in-prose] info: field carrier_ref"))
    (is (str/ends-with? line "Ask: When your payment processor says a payment cleared, what should happen?"))))

(deftest typed-after-the-fact
  (let [law (assoc-in (order-law paid-event) [:actions :record_payment]
                      {:from #{:open} :to :open
                       :input [:map [:payment_ref [:string {:min 1 :max 120}]]]
                       :waives #{:edit-shape}
                       :handler keep-the-ref
                       :safety once})
        [line :as lines] (of :typed-after-the-fact (said law))]
    (is (= 1 (count lines)))
    (is (str/includes? line "door record_payment takes only payment_ref"))
    (is (str/ends-with? line "Ask: Who types payment_ref today, and where do they copy it from?"))))

(deftest an-effect-names-its-key-and-its-failure-state
  (is (thrown-with-msg? Exception
                        #"\[effect-no-key\].*Ask: If this ran twice, how many postage__create_labels should there be\?"
                        (resource/resource (parcel-law (dissoc buy-label :idempotency-key)))))
  (is (thrown-with-msg? Exception
                        #"\[effect-no-failure-state\].*Ask: When postage__create_label fails, who should pick it up\?"
                        (resource/resource (parcel-law (dissoc buy-label :fails-to)))))
  (testing "a whole effect says nothing"
    (let [lines (said (parcel-law buy-label))]
      (doseq [f (keys checks/integration-findings)]
        (is (empty? (of f lines)) (name f))))))

(deftest unwinds-names-an-effect-with-its-undo
  (is (thrown-with-msg? Exception
                        #"\[unwinds-no-compensate\] cancel :unwinds buy_label.*Ask: What has to be reversed when this cancel happens\?"
                        (resource/resource
                         (parcel-law buy-label
                                     :cancel {:from #{:labelled} :to :packed
                                              :unwinds [{:effect :buy_label}]
                                              :safety once})))))

(deftest an-event-finds-its-row-and-walks-its-door
  (testing "[event-match-unfiltered]"
    (is (thrown-with-msg? Exception #"\[event-match-unfiltered\].*payment_ref.*Declare it filterable\."
                          (resource/resource
                           (assoc (order-law paid-event) :filterable {:state #{:eq :in}})))))
  (testing "[event-door-unreachable]: a required input the entry does not fill"
    (is (thrown-with-msg? Exception #"\[event-door-unreachable\].*without amount_cents"
                          (resource/resource (order-law (dissoc paid-event :input)))))))

(deftest refused-event-untold
  (let [[line :as lines] (of :refused-event-untold (said (order-law (dissoc paid-event :tell))))]
    (is (= 1 (count lines)))
    (is (str/includes? line "runs mark_paid and names no seat to :tell"))
    (is (str/ends-with? line "Ask: Who should hear when a payment cannot be applied?"))))

(deftest source-unsigned
  (is (= (str "[source-unsigned] warning: integration stripe signs with scheme none. "
              "Use a signing source, or keep it on :via :inbox so a seat reads each event.")
         (checks/integration-finding :source-unsigned "integration stripe signs with scheme none" {}))))
