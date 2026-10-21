(ns waymark10.integration-events-test
  "The receiver (docs/spec-integrations.md § 3, build step 2): a signed
  event keeps one receipt, a bad signature stores nothing, and a
  duplicate changes nothing. Memory storage, real rows through the
  engine."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :refer [defhandler defresource]]
            [waymark10.server.engine :as engine]
            [waymark10.server.integrations :as integrations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
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

;; ── the applier (build step 3) ──────────────────────────────────────

(defhandler keep-the-amount [row inp _ctx]
  (assoc-in row [:data :amount_cents] (:amount_cents inp)))

(defresource job
  {:kind :shop_order
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
                         :safety {:idempotent true :reversible false :confirm false
                                  :one-way "A paid job keeps its payment."}}}
   :on-event [{:source :stripe
               :type "payment_intent.succeeded"
               :match {:payment_ref '(event "data" "object" "id")}
               :door :mark_paid
               :input {:amount_cents '(event "data" "object" "amount_received")}}]})

(defn- job-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [job]
                  :now-fn (fn [] clock)}))

(defn- row-of [eng kind id]
  (let [st (:storage eng)]
    (some->> (store/with-tx st #(store/load-row st % kind (str id) {}))
             (inv/decode-row (get (inv/resources eng) kind)))))

(defn- job! [eng ref]
  (str (get-in (inv/create! eng :shop_order {:payment_ref ref} {:principal colton}) [:row :id])))

(defn- paid
  ([id ref] (paid id ref 1800))
  ([id ref amount]
   (str "{\"id\":\"" id "\",\"type\":\"payment_intent.succeeded\","
        "\"data\":{\"object\":{\"id\":\"" ref "\",\"amount_received\":" amount "}}}")))

(defn- applied!
  "Post a signed event, apply its receipt, and answer the receipt."
  [eng body]
  (let [rid (:receipt (post eng "stripe" (signed body) body))]
    (integrations/apply! eng rid)
    (row-of eng :integration_event rid)))

(deftest the-law-names-what-the-integration-may-do
  (let [eng (job-engine)
        irow (row-of eng :integration (live! eng nil))]
    (is (= "shop_order" (str (get-in irow [:data :scope_wanted 0 :kind]))))
    (is (= ["mark_paid"] (mapv str (get-in irow [:data :scope_wanted 0 :actions]))))
    (is (some? (get-in irow [:data :grant])) "and the approve minted its grant")))

(deftest a-refused-door-is-a-refused-receipt-with-the-refusal-whole
  (let [eng (job-engine)
        _ (live! eng nil)
        jid (job! eng "pi_1")
        first-one (applied! eng (paid "evt_1" "pi_1"))]
    (testing "the first event moves the row it matches"
      (is (= :applied (:state first-one)))
      (is (= jid (get-in first-one [:data :target])))
      (is (= "shop_order.mark_paid" (get-in first-one [:data :door])))
      (is (some? (get-in first-one [:data :transition])))
      (is (= :paid (:state (row-of eng :shop_order jid))))
      (is (= 1800 (get-in (row-of eng :shop_order jid) [:data :amount_cents]))))
    (testing "applying it again moves nothing"
      (integrations/apply! eng (str (:id first-one)))
      (is (= first-one (row-of eng :integration_event (:id first-one)))))
    (testing "a door that refuses the event is a refused receipt, the refusal kept whole"
      (let [jid (job! eng "pi_3")
            refused (applied! eng (paid "evt_2" "pi_3" 0))
            doc (wire/read-json (get-in refused [:data :refusal]))
            at (fn [k] (or (get doc k) (get doc (name k))))]
        (is (= :refused (:state refused)))
        (is (= jid (get-in refused [:data :target])))
        (is (string? (at :type)) (pr-str doc))
        (is (string? (at :title)) (pr-str doc))
        (is (integer? (at :status)) (pr-str doc))
        (is (= ["mom"] (get-in refused [:data :tell])) "with no :tell, the approver hears it")
        (is (= :open (:state (row-of eng :shop_order jid))) "and the row did not move")))))

(deftest a-receipt-no-door-takes-says-why
  (let [eng (job-engine)
        _ (live! eng nil)]
    (testing "no row matches"
      (is (= :unmatched (:state (applied! eng (paid "evt_3" "pi_9"))))))
    (testing "more than one row matches"
      (job! eng "pi_2")
      (job! eng "pi_2")
      (is (= :ambiguous (:state (applied! eng (paid "evt_4" "pi_2"))))))
    (testing "no entry names the type"
      (is (= :ignored (:state (applied! eng "{\"id\":\"evt_5\",\"type\":\"charge.refunded\"}")))))))

(deftest retry-applies-under-the-law-of-now-and-dismiss-lets-go
  (let [eng (job-engine)
        _ (live! eng nil)
        early (applied! eng (paid "evt_6" "pi_6"))
        rid (str (:id early))
        jid (job! eng "pi_6")]
    (is (= :unmatched (:state early)))
    (inv/invoke! eng :integration_event rid :retry {} {:principal mom})
    (integrations/apply! eng rid)
    (is (= :applied (:state (row-of eng :integration_event rid))))
    (is (= :paid (:state (row-of eng :shop_order jid))))
    (let [gone (str (:id (applied! eng (paid "evt_7" "pi_7"))))]
      (inv/invoke! eng :integration_event gone :dismiss
                   {:reason "That payment was for another shop."} {:principal mom})
      (is (= :dismissed (:state (row-of eng :integration_event gone))))
      (is (= "That payment was for another shop."
             (get-in (row-of eng :integration_event gone) [:data :reason]))))))
