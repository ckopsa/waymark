(ns waymark10.leases-test
  "Leases build ticket 1 (docs/spec-leases.md §1, §2, Settled): one
  live lease per subject; a stale lease is expired by the next acquire;
  a release with grace stays live until it runs out; a refused inner
  acquire rolls the outer write back; and the half-life rule. Needs the
  test database (WAYMARK10_TEST_DSN)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.leases :as leases]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private holder
  (r/resource {:kind :ls_holder
               :plural "ls_holders"
               :states [:open :closed]
               :initial :open
               :terminal #{:closed}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]]]
               :actions {:close {:from #{:open} :to :closed
                                 :safety {:idempotent true :reversible false :confirm false
                                          :one-way "A closed holder stays closed."}
                                 :display {:label "Close"}}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(def ^:private t0 (Instant/parse "2026-10-23T12:00:00Z"))

(defn- with-eng [f]
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["leases" "ls_holders" "waymark10_transitions"
                         "waymark10_idempotency" "waymark10_drafts"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (let [clock (atom t0)]
        (f (inv/engine {:storage st :resources [holder leases/lease]
                        :now-fn #(deref clock)})
           clock))
      (finally (pg/close! st)))))

(defn- holder! [eng nm]
  (get-in (inv/create! eng :ls_holder {:name nm} {:principal elena}) [:row :id]))

(defn- acquire! [eng holder-id subject]
  (get-in (inv/create! eng :lease {:holder_kind "ls_holder" :holder_id (str holder-id)
                                   :subject_kind "ticket" :subject_id subject}
                       {:principal elena})
          [:row :id]))

(defn- refusal [f]
  (try (f) nil
       (catch Exception e (pr-str (or (ex-data e) (ex-message e))))))

(defn- lease [eng id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (some->> (store/load-row (:storage eng) tx :lease (str id) {})
               (inv/decode-row leases/lease)))))

(deftest one-live-lease-per-subject
  (with-eng
    (fn [eng _]
      (let [a (holder! eng "a")
            b (holder! eng "b")
            first-lease (acquire! eng a "t-1")]
        (is (= :held (keyword (name (:state (lease eng first-lease))))))
        (testing "a second acquire is refused subject_held, naming the holder"
          (let [why (refusal #(acquire! eng b "t-1"))]
            (is (some? why))
            (is (re-find #"subject[-_]held" (str why)))))
        (testing "another subject is free"
          (is (some? (acquire! eng b "t-2"))))))))

(deftest a-stale-lease-is-expired-by-the-next-acquire
  (with-eng
    (fn [eng _]
      (let [a (holder! eng "a")
            b (holder! eng "b")
            stale (acquire! eng a "t-1")]
        (inv/invoke! eng :ls_holder a :close {} {:principal elena})
        (let [fresh (acquire! eng b "t-1")
              old (lease eng stale)]
          (is (some? fresh))
          (is (= :ended (keyword (name (:state old)))))
          (is (= "expired" (get-in old [:data :end_reason]))))))))

(deftest a-release-with-grace-stays-live-until-it-runs-out
  (with-eng
    (fn [eng clock]
      (let [a (holder! eng "a")
            b (holder! eng "b")
            held (acquire! eng a "t-1")]
        (inv/invoke! eng :lease held :release {:grace_seconds 60} {:principal elena})
        (testing "inside the grace the subject is still held"
          (swap! clock #(.plusSeconds ^Instant % 59))
          (is (some? (refusal #(acquire! eng b "t-1")))))
        (testing "past it the next acquire takes the subject"
          (swap! clock #(.plusSeconds ^Instant % 2))
          (is (some? (acquire! eng b "t-1")))
          (is (= "released" (get-in (lease eng held) [:data :end_reason]))))))))

(deftest a-refused-inner-acquire-rolls-back-the-outer-write
  (with-eng
    (fn [eng _]
      (let [a (holder! eng "a")
            _ (acquire! eng a "t-1")
            sink (atom [])
            opts {:principal leases/leaser :sink sink}
            why (refusal
                 #(store/with-tx (:storage eng)
                    (fn [tx]
                      (let [b (get-in (inv/create-in! eng tx :ls_holder {:name "b"} opts)
                                      [:row :id])]
                        (inv/create-in! eng tx :lease
                                        {:holder_kind "ls_holder" :holder_id (str b)
                                         :subject_kind "ticket" :subject_id "t-1"}
                                        opts)))))]
        (is (some? why))
        (is (= 1 (count (store/with-tx (:storage eng)
                          (fn [tx] (store/query-rows (:storage eng) tx :ls_holder {} {:limit 10}))))))
        (testing "the doors take the engine's own actor only"
          (is (thrown? Exception
                       (store/with-tx (:storage eng)
                         (fn [tx] (inv/create-in! eng tx :ls_holder {:name "c"}
                                                  {:principal elena}))))))))))

(deftest half-left?-is-the-half-life-rule
  (is (leases/half-left? t0 (.plusSeconds t0 1800) 3600))
  (is (not (leases/half-left? t0 (.plusSeconds t0 1799) 3600)))
  (is (leases/half-left? t0 nil 3600))
  (is (leases/half-left? t0 (str (.plusSeconds t0 10)) 0)))
