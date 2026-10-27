(ns waymark10.guard-writers-test
  "A guard that names no remedy still says where the field it reads is
  written (ticket 7cc2315b): the door it guards when that door takes
  the field, else the nearest door before it, else create — and the
  audit warns [guard-create-only] when only create can, and fails
  [guard-unwritable] when no door can. Over a lamp whose `light` reads
  (data :lit_ok), the shape of dress_rehearsal's prospect.engage."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.checks :as checks]
            [waymark10.guards :as g]
            [waymark10.resource :as r]))

(def ^:private routine {:idempotent true :reversible true :confirm false})

(def ^:private ok-to-light
  (g/expr {:name :ok-to-light
           :when '(= (data :lit_ok) true)
           :explain "The lamp lights once someone says it may."}))

(def ^:private takes-lit-ok
  [:map [:lit_ok {:optional true} [:maybe :boolean]]])

(defn- lamp [kind & {:keys [light-input prep create-schema]}]
  (cond-> {:kind kind
           :states [:off :on]
           :initial :off
           :summary "Lamp · {state}"
           :schema [:map
                    [:name {:optional true} [:maybe :string]]
                    [:lit_ok {:optional true} [:maybe :boolean]]]
           :actions
           (cond-> {:light (cond-> {:from #{:off} :to :on
                                    :guards [ok-to-light]
                                    :safety routine}
                             light-input (assoc :input takes-lit-ok))
                    :dim {:from #{:on} :to :off :safety routine}}
             prep (assoc :prep {:from #{:off} :to :off
                                :input takes-lit-ok
                                :safety routine}))}
    create-schema (assoc :create-schema create-schema)))

(defn- refusal [rdef]
  (let [d (g/with-writers rdef :light ok-to-light)]
    {:remedies (g/remedy-doors d)
     :resolved (g/resolve-remedies d nil {})
     :reason (g/writers-reason d "The lamp lights once someone says it may.")}))

(defn- create-only-warnings [rdef]
  (filterv #(str/includes? % "[guard-create-only]")
           (:warnings (checks/run-all rdef))))

(deftest a-field-only-create-takes-sends-the-refusal-to-create-and-warns
  (let [rdef (r/resource (lamp :born_lamp))
        {:keys [remedies resolved]} (refusal rdef)]
    (is (= [:born_lamp/create] remedies))
    (is (= [{:door :born_lamp/create :fields ["lit_ok"]}] resolved))
    (let [ws (create-only-warnings rdef)]
      (is (= 1 (count ws)) (pr-str ws))
      (is (str/includes? (first ws) "ok-to-light on born_lamp.light reads lit_ok")))))

(deftest a-field-the-guarded-door-takes-names-that-door-and-clears-the-warning
  (let [rdef (r/resource (lamp :own_lamp :light-input true))]
    (is (= [{:door :own_lamp/light :fields ["lit_ok"]}]
           (:resolved (refusal rdef))))
    (is (empty? (create-only-warnings rdef)))))

(deftest a-field-an-earlier-door-takes-names-that-door
  (let [rdef (r/resource (lamp :prepped_lamp :prep true))]
    (is (= [{:door :prepped_lamp/prep :fields ["lit_ok"]}]
           (:resolved (refusal rdef))))
    (is (empty? (create-only-warnings rdef)))))

(deftest a-field-the-schema-marks-engine-written-is-not-create-only
  (let [rdef (r/resource
              (assoc (lamp :engine_lamp)
                     :schema [:map
                              [:name {:optional true} [:maybe :string]]
                              [:lit_ok {:optional true :engine-written true} [:maybe :boolean]]]))]
    (is (g/engine-written? rdef :lit_ok))
    (is (empty? (create-only-warnings rdef)))))

(deftest a-field-no-door-takes-fails-the-battery-and-has-no-remedy
  (let [m (lamp :mute_lamp :create-schema [:map [:name {:optional true} [:maybe :string]]])]
    (is (thrown-with-msg? Exception #"guard-unwritable" (r/resource m)))
    (testing "the refusal keeps no remedy and its reason says why"
      (let [{:keys [remedies reason]} (refusal m)]
        (is (= [] remedies))
        (is (str/includes? reason "No door takes lit_ok"))))))

(def ^:private checked
  (g/expr {:name :checked
           :when '(is-set (data :checked_by))
           :explain "The lamp lights once someone has checked it."}))

(defn- checked-lamp [kind field stamp & {:keys [create-schema]
                                          :or {create-schema [:map [:name {:optional true} [:maybe :string]]]}}]
  (cond-> {:kind kind
           :states [:off :on]
           :initial :off
           :summary "Lamp · {state}"
           :schema [:map
                    [:name {:optional true} [:maybe :string]]
                    field]
           :actions {:check (cond-> {:from #{:off} :to :off :safety routine}
                              stamp (assoc :stamp {:checked_by '(actor)}))
                     :light {:from #{:off} :to :on :guards [checked] :safety routine}
                     :dim {:from #{:on} :to :off :safety routine}}}
    create-schema (assoc :create-schema create-schema)))

(deftest a-principal-ref-read-at-a-later-door-is-unwritable
  (let [m (checked-lamp :principal_lamp
                        [:checked_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]
                        false
                        :create-schema nil)]
    (is (thrown-with-msg? Exception
                          #"guard-unwritable.*checked_by is a principal ref, which only create stamps; to record who acts at light"
                          (r/resource m)))
    (testing "a principal the create stamps, its :create-schema leaving it out, is written"
      (is (some? (r/resource (assoc m :kind :owned_lamp
                                    :create-schema [:map [:name {:optional true} [:maybe :string]]])))))))

(deftest a-stamped-member-ref-read-at-a-later-door-is-written
  (let [m (checked-lamp :member_lamp
                        [:checked_by {:optional true :kind :member} [:maybe :waymark/ref]]
                        true)]
    (is (some? (r/resource m)))))
