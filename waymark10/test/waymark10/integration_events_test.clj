(ns waymark10.integration-events-test
  "The receiver (docs/spec-integrations.md § 3, build step 2): a signed
  event keeps one receipt, a bad signature stores nothing, and a
  duplicate changes nothing. Memory storage, real rows through the
  engine."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.engine :as engine]
            [waymark10.server.integrations :as integrations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant)))

(def ^:private clock (Instant/parse "2026-10-21T09:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private mom (t/principal {:id "mom" :display "Mom"}))

(def ^:private signing-key "whsec_test_key")

(def ^:private event
  "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\"}}}")

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources []
                  :now-fn (fn [] clock)}))

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- live!
  "A live integration named stripe, signing with `signing-key`."
  [eng signature]
  (let [sec (str (get-in (inv/create! eng :secret
                                      {:name "STRIPE_SIGNING" :value signing-key}
                                      {:principal colton})
                         [:row :id]))
        id (str (get-in (inv/create! eng :integration
                                     (cond-> {:name "stripe"
                                              :system "Stripe, the payment processor"
                                              :secret sec
                                              :event_id_path ["id"]
                                              :event_type_path ["type"]}
                                       signature (assoc :signature signature))
                                     {:principal colton})
                        [:row :id]))]
    (inv/invoke! eng :integration id :approve {} {:principal mom})
    id))

(defn- receipts [eng]
  (let [rd (get (inv/resources eng) :integration_event)
        st (:storage eng)]
    (mapv #(inv/decode-row rd %)
          (store/with-tx st
            (fn [tx] (store/query-rows st tx :integration_event {} {:limit 100}))))))

(defn- post
  "What the receiver answers, or the problem it refused with."
  [eng nm headers body]
  (try (integrations/receive! eng nm headers (utf8 body))
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- signed [body]
  {"x-signature" (integrations/sign signing-key (utf8 body))})

(deftest a-signed-event-keeps-one-receipt
  (let [eng (fresh-engine)
        iid (live! eng nil)
        answer (post eng "stripe" (signed event) event)
        [r :as rs] (receipts eng)]
    (is (= 202 (:status answer)))
    (is (false? (:duplicate answer)))
    (is (= 1 (count rs)))
    (is (= (:receipt answer) (str (:id r))))
    (is (= :received (:state r)))
    (is (= iid (str (get-in r [:data :integration]))))
    (is (= "evt_1" (get-in r [:data :event_id])))
    (is (= "payment_intent.succeeded" (get-in r [:data :event_type])))
    (is (= event (get-in r [:data :body])))
    (is (= (count (utf8 event)) (get-in r [:data :bytes])))))

(deftest a-bad-signature-stores-nothing
  (let [eng (fresh-engine)
        _ (live! eng nil)]
    (testing "signed with another key"
      (is (= 401 (:status (post eng "stripe"
                                {"x-signature" (integrations/sign "not-the-key" (utf8 event))}
                                event)))))
    (testing "signed over another body"
      (is (= 401 (:status (post eng "stripe" (signed "{}") event)))))
    (testing "not signed at all"
      (is (= 401 (:status (post eng "stripe" {} event)))))
    (is (empty? (receipts eng)) "no receipt was kept")))

(deftest a-duplicate-changes-nothing
  (let [eng (fresh-engine)
        _ (live! eng nil)
        first-answer (post eng "stripe" (signed event) event)
        before (receipts eng)
        again (post eng "stripe" (signed event) event)]
    (is (= 200 (:status again)))
    (is (true? (:duplicate again)))
    (is (= (:receipt first-answer) (:receipt again)) "it answers the first receipt")
    (is (= before (receipts eng)) "and the receipts are as they were")))

(deftest a-stale-timestamp-is-refused
  (let [eng (fresh-engine)
        _ (live! eng {:timestamp_header "X-Timestamp" :tolerance_s 300})
        at (fn [secs] (assoc (signed event) "x-timestamp" (str secs)))
        now (.getEpochSecond clock)]
    (is (= 401 (:status (post eng "stripe" (at (- now 301)) event))))
    (is (= 401 (:status (post eng "stripe" (signed event) event))) "no stamp at all")
    (is (empty? (receipts eng)))
    (is (= 202 (:status (post eng "stripe" (at (- now 10)) event))))))

(deftest a-paused-or-unknown-integration-keeps-nothing
  (let [eng (fresh-engine)
        iid (live! eng nil)]
    (is (= 404 (:status (post eng "shippo" (signed event) event))))
    (inv/invoke! eng :integration iid :pause {} {:principal mom})
    (is (= 503 (:status (post eng "stripe" (signed event) event))))
    (is (empty? (receipts eng)))))

(deftest nobody-writes-a-receipt-by-hand
  (let [eng (fresh-engine)
        iid (live! eng nil)]
    (is (thrown? Exception
                 (inv/create! eng :integration_event
                              {:integration iid :event_id "evt_forged" :bytes 0}
                              {:principal mom})))
    (is (empty? (receipts eng)))))
