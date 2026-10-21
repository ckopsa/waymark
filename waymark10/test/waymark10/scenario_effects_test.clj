(ns waymark10.scenario-effects-test
  "Scenario :events and :effects (docs/spec-integrations.md §6, build
  step 8): a scenario replays recorded events through the kind's
  :on-event, answers each effect call from its fake, and lists the calls
  it made — and one that reaches an effect door with no fake for its
  power is refused, as a timed law with no :at is."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :refer [defhandler defresource]]
            [waymark10.scenario :as scenario]))

(def ^:private once
  {:idempotent true :reversible false :confirm false
   :one-way "A parcel's label is bought once."})

(defhandler sx-keep-the-delivery [row inp _ctx]
  (assoc-in row [:data :shipped_at] (:shipped_at inp)))

(defresource sx-shipment
  {:kind :sx_shipment
   :states [:packed :label_asked :labelled :label_failed :shipped :refunded
            :label_voided :void_failed]
   :initial :packed
   :summary "{data.number} · {state}"
   :schema [:map
            [:number [:string {:min 1 :max 40}]]
            [:label_ref {:optional true} [:maybe [:string {:max 120}]]]
            [:shipped_at {:optional true} [:maybe [:string {:max 40}]]]]
   :filterable {:state #{:eq :in} :number #{:eq} :label_ref #{:eq}}
   :actions {:buy_label {:from #{:packed} :to :label_asked
                         :safety once
                         :effect {:power "postage__create_label"
                                  :input {:number '(data :number)}
                                  :idempotency-key '(str "label:" (self))
                                  :writes {:label_ref '(result "object_id")}
                                  :lands :labelled
                                  :fails-to :label_failed
                                  :compensate :void_label}}
             :void_label {:from #{:labelled :label_asked} :to :label_voided
                          :safety once
                          :effect {:power "postage__refund_label"
                                   :input {:label '(data :label_ref)}
                                   :idempotency-key '(str "void:" (self))
                                   :fails-to :void_failed}}
             :ship {:from #{:labelled} :to :shipped
                    :input [:map [:shipped_at [:string {:min 1 :max 40}]]]
                    :waives #{:edit-shape}
                    :handler sx-keep-the-delivery
                    :safety once}
             :refund {:from #{:labelled :shipped} :to :refunded
                      :safety once
                      :unwinds [{:effect :buy_label :when '(not (is-set (data :shipped_at)))}]}
             :reopen {:from #{:label_failed :refunded :label_voided :void_failed} :to :packed
                      :safety once}}
   :on-event [{:source :shop
               :type "order.paid"
               :match {:number '(event "number")}
               :door :buy_label
               :input {}}
              {:source :carrier
               :type "tracker.delivered"
               :match {:label_ref '(event "label")}
               :door :ship
               :input {:shipped_at '(event "at")}}]})

(def ^:private label {"postage__create_label" {:answer {"object_id" "L1"}}})
(def ^:private both (assoc label "postage__refund_label" {:answer {"refund_id" "R1"}}))
(def ^:private paid {:source :shop :type "order.paid" :body {:number "P-1"}})
(def ^:private delivered
  {:source :carrier :type "tracker.delivered" :body {:label "L1" :at "2026-10-21"}})

(defn- refund [events effects expect]
  (scenario/scenario :a-refund "A refund voids the label of a parcel that never shipped."
                     (cond-> {:kind :sx_shipment :attempt :refund
                              :row {:state :packed :data {:number "P-1"}}
                              :as {:id "mom" :type :person}
                              :events events
                              :expect expect}
                       effects (assoc :effects effects))))

(defn- refusal [f]
  (try (f) nil (catch Exception e (ex-message e))))

(deftest a-refund-voids-an-unshipped-label
  (let [s (refund [paid] both {:allowed true})
        calls (scenario/effect-calls sx-shipment s)]
    (is (scenario/check-tier? sx-shipment s) "judged here, not deferred")
    (is (= [] (scenario/judge sx-shipment s)))
    (is (= [["buy_label" "postage__create_label" "label:scenario:a-refund" :done]
            ["void_label" "postage__refund_label" "undo:label:scenario:a-refund" :done]]
           (mapv (juxt :door :power :key :ending) calls))
        "one label, then one void")
    (is (= {:number "P-1"} (:input (first calls))))
    (is (= {:label "L1"} (:input (second calls))) "the void names the label the fake answered")))

(deftest a-shipped-parcel-keeps-its-label
  (let [s (refund [paid delivered] both {:allowed true})]
    (is (= [] (scenario/judge sx-shipment s)))
    (is (= ["buy_label"] (mapv :door (scenario/effect-calls sx-shipment s))))))

(deftest each-fake-lands-as-the-runner-would
  (testing "a failed call holds the row in its :fails-to"
    (let [s (refund [paid] {"postage__create_label" {:fails "The address was not found."}}
                    {:refused :out-of-state :because "Label failed"})]
      (is (= [] (scenario/judge sx-shipment s)))
      (is (= [[:failed "The address was not found."]]
             (mapv (juxt :ending :reason) (scenario/effect-calls sx-shipment s))))))
  (testing "an unknown ending leaves the row asked"
    (is (= [] (scenario/judge sx-shipment (refund [paid] {"postage__create_label" :unknown}
                                                  {:refused :out-of-state :because "Label asked"}))))))

(deftest an-effect-law-without-a-fake-is-refused
  (testing "the attempt's own call"
    (let [s (scenario/scenario :buy "A packed parcel buys its label."
                               {:kind :sx_shipment :attempt :buy_label
                                :row {:state :packed :data {:number "P-1"}}
                                :expect {:allowed true}})]
      (is (str/includes? (first (scenario/judge sx-shipment s))
                         "reaches buy_label, which calls postage__create_label, and :effects names no fake for it"))))
  (testing "a compensation's call"
    (is (str/includes? (first (scenario/judge sx-shipment (refund [paid] label {:allowed true})))
                       "reaches void_label, which calls postage__refund_label")))
  (testing "an event's door"
    (is (str/includes? (first (scenario/judge sx-shipment (refund [paid] nil {:allowed true})))
                       "reaches buy_label"))))

(deftest an-event-that-moves-nothing-says-how-it-ended
  (is (str/includes? (first (scenario/judge sx-shipment (refund [(assoc paid :type "order.refunded")] both {:allowed true})))
                     "lands ignored"))
  (is (str/includes? (first (scenario/judge sx-shipment (refund [(assoc paid :body {:number "P-2"})] both {:allowed true})))
                     "lands unmatched"))
  (is (str/includes? (first (scenario/judge sx-shipment (refund [paid paid] both {:allowed true})))
                     "lands refused")))

(deftest malformed-events-and-fakes-refuse-where-they-are-written
  (is (str/includes? (refusal #(refund [(assoc paid :signed true)] both {:allowed true})) "not event keys"))
  (is (str/includes? (refusal #(refund [(dissoc paid :type)] both {:allowed true})) ":type"))
  (is (str/includes? (refusal #(refund [paid] {"postage__create_label" {:answer {} :fails "No."}} {:allowed true}))
                     "is one of"))
  (is (str/includes? (refusal #(refund [paid] {:postage {:answer {}}} {:allowed true})) "as a string")))
