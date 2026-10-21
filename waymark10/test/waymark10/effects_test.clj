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

(deftest an-effect-names-its-key-and-its-failure-state
  (is (thrown-with-msg? Exception #"effect-no-key"
                        (resource/resource (parcel-law (dissoc buy-label :idempotency-key)))))
  (is (thrown-with-msg? Exception #"effect-no-failure-state"
                        (resource/resource (parcel-law (dissoc buy-label :fails-to)))))
  (is (thrown-with-msg? Exception #"not a state"
                        (resource/resource (parcel-law (assoc buy-label :lands :shipped))))))
