(ns waymark10.effects-test
  "The outbox (docs/spec-integrations.md § 4, build step 5): an effect
  door's transition and one `effect_call` commit in one transaction; a
  second ask under the same key keeps no second call, a `done` key's
  answer is written again, and the probe says what it would call.
  Memory storage, real rows through the engine."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as resource :refer [defresource]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private clock (Instant/parse "2026-10-21T09:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private runner
  (t/principal {:id "test-runner" :type :system :display "Runner"}))

(def ^:private once
  {:idempotent true :reversible false :confirm false
   :one-way "A parcel's label is bought once."})

(def ^:private buy-label
  {:power "postage__create_label"
   :input {:number '(data :number)}
   :why "Buy the postage label for parcel {number}."
   :idempotency-key '(str "label:" (self))
   :writes {:label_ref '(result "object_id")}
   :lands :labelled
   :fails-to :label_failed})

(defn- parcel-law [effect]
  {:kind :parcel
   :states [:packed :label_asked :labelled :label_failed]
   :initial :packed
   :summary "{data.number} · {state}"
   :schema [:map
            [:number [:string {:min 1 :max 40}]]
            [:label_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :actions {:buy_label {:from #{:packed} :to :label_asked
                         :safety once
                         :effect effect}
             :repack {:from #{:label_asked :labelled :label_failed} :to :packed
                      :safety once}}})

(defresource parcel (parcel-law buy-label))

(defn- parcel-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [parcel]
                  :now-fn (fn [] clock)}))

(defn- calls [eng]
  (let [rd (get (inv/resources eng) :effect_call)
        st (:storage eng)]
    (mapv #(inv/decode-row rd %)
          (store/with-tx st
            (fn [tx] (store/query-rows st tx :effect_call {} {:limit 100}))))))

(defn- row-of [eng id]
  (let [st (:storage eng)]
    (some->> (store/with-tx st #(store/load-row st % :parcel (str id) {}))
             (inv/decode-row (get (inv/resources eng) :parcel)))))

(defn- parcel! [eng number]
  (str (get-in (inv/create! eng :parcel {:number number} {:principal colton})
               [:row :id])))

(defn- door! [eng id door]
  (inv/invoke! eng :parcel id door {} {:principal colton}))

(deftest two-invokes-one-effect-call
  (let [eng (parcel-engine)
        pid (parcel! eng "P-1")]
    (door! eng pid :buy_label)
    (testing "the door and its call commit together"
      (is (= :label_asked (:state (row-of eng pid))))
      (let [[c :as cs] (calls eng)]
        (is (= 1 (count cs)))
        (is (= :pending (:state c)))
        (is (= "postage__create_label" (get-in c [:data :power])))
        (is (= (str "label:" pid) (get-in c [:data :idempotency_key])))
        (is (= "Buy the postage label for parcel P-1." (get-in c [:data :why])))
        (is (re-find #"P-1" (str (get-in c [:data :input]))) "its input read from the row")
        (is (= "colton" (get-in c [:data :on_behalf_of])))
        (is (= ["parcel" pid "buy_label"]
               (mapv #(str (get-in c [:data %])) [:target_kind :target :door])))))
    (testing "asking again under the same key keeps no second call"
      (door! eng pid :repack)
      (door! eng pid :buy_label)
      (is (= :label_asked (:state (row-of eng pid))))
      (is (= 1 (count (calls eng)))))))

(deftest the-row-read-shows-its-pending-effect
  (let [eng (parcel-engine)
        h (engine/handler eng)
        pid (parcel! eng "P-5")
        read-meta #(:meta (wire/read-json
                           (:body (h {:request-method :get
                                      :uri (str "/api/parcels/" pid)
                                      :headers {"x-waymark-principal" "colton"}}))))]
    (is (nil? (:effects (read-meta))) "a row that asked for nothing shows nothing")
    (door! eng pid :buy_label)
    (let [[e :as es] (:effects (read-meta))]
      (is (= 1 (count es)))
      (is (= "postage__create_label" (:power e)))
      (is (= "pending" (:state e)))
      (is (string? (:since e))))
    (inv/invoke! eng :effect_call (str (:id (first (calls eng)))) :mark_done
                 {:answer "{\"object_id\":\"L5\"}"} {:principal runner})
    (is (nil? (:effects (read-meta))) "a call that ended is no longer pending")))

(deftest a-done-key-writes-its-answer-again
  (let [eng (parcel-engine)
        pid (parcel! eng "P-2")
        _ (door! eng pid :buy_label)
        cid (str (:id (first (calls eng))))]
    (inv/invoke! eng :effect_call cid :mark_done {:answer "{\"object_id\":\"L1\"}"}
                 {:principal runner})
    (door! eng pid :repack)
    (door! eng pid :buy_label)
    (let [row (row-of eng pid)]
      (is (= :labelled (:state row)) "the hidden door landed the answer it reused")
      (is (= "L1" (get-in row [:data :label_ref]))))
    (is (= 1 (count (calls eng))) "and no second call was kept")))

(deftest the-probe-says-what-it-would-call
  (let [eng (parcel-engine)
        pid (parcel! eng "P-3")
        res (inv/invoke! eng :parcel pid :buy_label {} {:principal colton :dry-run true})]
    (is (:valid? res))
    (is (= [(str "Would call postage__create_label once, key label:" pid ".")]
           (mapv :says (:effects res))))
    (is (empty? (calls eng)) "it called nothing and kept nothing")
    (is (= :packed (:state (row-of eng pid))))))

(deftest only-the-engine-lands-an-effect
  (let [eng (parcel-engine)
        pid (parcel! eng "P-4")]
    (door! eng pid :buy_label)
    (is (thrown? clojure.lang.ExceptionInfo
                 (inv/invoke! eng :parcel pid :buy_label_landed {:effect_call "c1"}
                              {:principal colton})))
    (is (= :label_asked (:state (row-of eng pid))))
    (inv/invoke! eng :parcel pid :buy_label_failed
                 {:effect_call "c1" :reason "The address was not found."}
                 {:principal runner})
    (is (= :label_failed (:state (row-of eng pid))))))

(def ^:private void-label
  {:power "postage__refund_label"
   :input {:label '(data :label_ref)}
   :idempotency-key '(str "void:" (self))
   :fails-to :void_failed})

(defn- shipment-law [unwinds]
  {:kind :shipment
   :states [:packed :label_asked :labelled :label_failed :shipped :refunded
            :label_voided :void_failed]
   :initial :packed
   :summary "{data.number} · {state}"
   :schema [:map
            [:number [:string {:min 1 :max 40}]]
            [:label_ref {:optional true} [:maybe [:string {:max 120}]]]
            [:shipped_at {:optional true} [:maybe [:string {:max 40}]]]]
   :actions {:buy_label {:from #{:packed} :to :label_asked
                         :safety once
                         :effect (assoc buy-label :compensate :void_label)}
             :void_label {:from #{:labelled :label_asked} :to :label_voided
                          :safety once
                          :effect void-label}
             :ship {:from #{:labelled} :to :shipped :safety once}
             :refund {:from #{:labelled :shipped} :to :refunded
                      :safety once
                      :unwinds unwinds}
             :reopen {:from #{:label_failed :refunded :label_voided :void_failed} :to :packed
                      :safety once}}})

(defresource shipment
  (shipment-law [{:effect :buy_label :when '(not (is-set (data :shipped_at)))}]))

(defn- labelled! [eng fields]
  (let [sid (str (get-in (inv/create! eng :shipment fields {:principal colton}) [:row :id]))
        _ (inv/invoke! eng :shipment sid :buy_label {} {:principal colton})
        cid (some #(when (= sid (str (get-in % [:data :target]))) (str (:id %))) (calls eng))
        answer "{\"object_id\":\"L-" ]
    (inv/invoke! eng :effect_call cid :mark_done {:answer (str answer sid "\"}")}
                 {:principal runner})
    (inv/invoke! eng :shipment sid :buy_label_landed
                 {:effect_call cid :answer (wire/read-json (str answer sid "\"}"))}
                 {:principal runner})
    sid))

(defn- shipment-of [eng id]
  (let [st (:storage eng)]
    (some->> (store/with-tx st #(store/load-row st % :shipment (str id) {}))
             (inv/decode-row (get (inv/resources eng) :shipment)))))

(defn- calls-on [eng id]
  (filterv #(= (str id) (str (get-in % [:data :target]))) (calls eng)))

(deftest a-refund-voids-an-unshipped-label-and-not-a-shipped-one
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources [shipment]
                            :now-fn (fn [] clock)})]
    (testing "an unshipped label: the refund adds its void, keyed undo:"
      (let [sid (labelled! eng {:number "S-1"})]
        (is (= :labelled (:state (shipment-of eng sid))))
        (inv/invoke! eng :shipment sid :refund {} {:principal colton})
        (is (= :refunded (:state (shipment-of eng sid))))
        (let [undo (some #(when (= "postage__refund_label" (get-in % [:data :power])) %)
                         (calls-on eng sid))]
          (is (= 2 (count (calls-on eng sid))))
          (is (= :pending (:state undo)))
          (is (= (str "undo:label:" sid) (get-in undo [:data :idempotency_key])))
          (is (= "void_label" (str (get-in undo [:data :door]))))
          (is (re-find (re-pattern (str "L-" sid)) (str (get-in undo [:data :input])))
              "its input read from the row")
          (testing "and a void that fails holds the row, the refund standing"
            (inv/invoke! eng :effect_call (str (:id undo)) :mark_failed
                         {:reason "The carrier refused the refund."} {:principal runner})
            (inv/invoke! eng :shipment sid :void_label_failed
                         {:effect_call (str (:id undo)) :reason "The carrier refused the refund."}
                         {:principal runner})
            (is (= :void_failed (:state (shipment-of eng sid))))
            (is (= :failed (:state (some #(when (= (:id undo) (:id %)) %) (calls eng)))))))))
    (testing "a shipped label: the refund voids nothing"
      (let [sid (labelled! eng {:number "S-2" :shipped_at "2026-10-20"})]
        (inv/invoke! eng :shipment sid :ship {} {:principal colton})
        (inv/invoke! eng :shipment sid :refund {} {:principal colton})
        (is (= :refunded (:state (shipment-of eng sid))))
        (is (= ["postage__create_label"]
               (mapv #(get-in % [:data :power]) (calls-on eng sid))))))))

(def ^:private cancel-label
  {:power "postage__cancel_label"
   :input {:label '(data :label_ref)}
   :fails-to :void_failed})

(deftest a-refund-whose-undo-is-a-power-adds-that-call
  (let [law (assoc-in (shipment-law [{:effect :buy_label}])
                      [:actions :buy_label :effect :compensate] cancel-label)
        eng (engine/engine {:storage (memory/storage)
                            :resources [(resource/resource law)]
                            :now-fn (fn [] clock)})
        sid (labelled! eng {:number "S-3"})]
    (inv/invoke! eng :shipment sid :refund {} {:principal colton})
    (is (= :refunded (:state (shipment-of eng sid))))
    (let [undo (some #(when (= "postage__cancel_label" (get-in % [:data :power])) %)
                     (calls-on eng sid))]
      (is (= 2 (count (calls-on eng sid))))
      (is (= :pending (:state undo)))
      (is (= (str "undo:label:" sid) (get-in undo [:data :idempotency_key])))
      (is (= "buy_label_undo" (str (get-in undo [:data :door]))))
      (is (re-find (re-pattern (str "L-" sid)) (str (get-in undo [:data :input])))
          "its input read from the row")
      (testing "and an undo that fails holds the row in its :fails-to"
        (inv/invoke! eng :effect_call (str (:id undo)) :mark_failed
                     {:reason "The carrier refused the cancel."} {:principal runner})
        (inv/invoke! eng :shipment sid :buy_label_undo_failed
                     {:effect_call (str (:id undo)) :reason "The carrier refused the cancel."}
                     {:principal runner})
        (is (= :void_failed (:state (shipment-of eng sid))))))))

(deftest a-power-undo-names-its-failure-state
  (is (thrown-with-msg? Exception #"effect-no-failure-state"
                        (resource/resource
                         (assoc-in (shipment-law [{:effect :buy_label}])
                                   [:actions :buy_label :effect :compensate]
                                   (dissoc cancel-label :fails-to)))))
  (is (thrown-with-msg? Exception #"is not one"
                        (resource/resource
                         (assoc-in (shipment-law [{:effect :buy_label}])
                                   [:actions :buy_label :effect :compensate] :ship)))))

(deftest unwinds-names-an-effect-with-its-undo
  (is (thrown-with-msg? Exception #"unwinds-no-compensate"
                        (resource/resource
                         (assoc-in (shipment-law [{:effect :void_label}])
                                   [:actions :void_label :effect :idempotency-key]
                                   '(str "void:" (self))))))
  (is (thrown-with-msg? Exception #"not an effect door"
                        (resource/resource (shipment-law [{:effect :ship}])))))

(deftest an-effect-names-its-key-and-its-failure-state
  (is (thrown-with-msg? Exception #"effect-no-key"
                        (resource/resource (parcel-law (dissoc buy-label :idempotency-key)))))
  (is (thrown-with-msg? Exception #"effect-no-failure-state"
                        (resource/resource (parcel-law (dissoc buy-label :fails-to)))))
  (is (thrown-with-msg? Exception #"not a state"
                        (resource/resource (parcel-law (assoc buy-label :lands :shipped))))))
