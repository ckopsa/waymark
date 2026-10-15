(ns waymark10.at-rest-test
  "At-rest encryption (ticket dbeb2f25): a declared field round-trips
  sealed through real Postgres, a wrong key fails loudly, no key keeps
  plaintext and says so, the backfill is idempotent, the input digest
  is keyed, and resolve-refs! still forwards the plaintext."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.at-rest :as at-rest]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.secrets :as secrets]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.util Base64)))

;; ── the world ───────────────────────────────────────────────────────

(defn- key-from [start]
  (at-rest/parse-key
   (.encodeToString (Base64/getEncoder) (byte-array (range start (+ start 32))))))

(def ^:private k1 (key-from 0))
(def ^:private k2 (key-from 32))

(defmacro ^:private with-key [k & body]
  `(let [was# (at-rest/use-key! ~k)]
     (try ~@body (finally (at-rest/use-key! was#)))))

(def ^:private the-token "tok-0123456789abcdef")

(def ^:private vault-decl
  {:kind :ar_vault
   :plural "ar_vaults"
   :states [:held :spent]
   :initial :held
   :terminal #{:spent}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 40}]]
            [:token {:optional true :secret true :at-rest :encrypted
                     :x-display {:label "Token"}}
             [:maybe [:string {:min 1 :max 400}]]]]
   :actions {:spend {:from #{:held} :to :spent
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Spent is history."}}}})

(def ^:private vault (r/resource vault-decl))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["ar_vaults" "secrets"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(defn- with-eng [f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine {:storage st :resources [vault]}))
      (finally (pg/close! st)))))

(defn- raw
  "What the database itself holds in one field: no store between."
  [eng table id field]
  (store/with-tx (:storage eng)
    (fn [tx]
      (-> (jdbc/execute-one! tx [(str "SELECT data->>'" field "' AS v FROM " table
                                      " WHERE id::text = ?")
                                 (str id)])
          vals first))))

(defn- stored [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :ar_vault (str id) {}))))

(defn- a-vault! [eng]
  (str (:id (:row (inv/create! eng :ar_vault {:name "a" :token the-token}
                               {:principal colton})))))

;; ── the value ───────────────────────────────────────────────────────

(deftest a-value-seals-and-opens-under-its-key
  (with-key k1
    (let [s (at-rest/seal the-token)]
      (is (str/starts-with? s (str "enc:v1:" (:id k1) ":")))
      (is (not (str/includes? s "0123456789")))
      (is (not= s (at-rest/seal the-token)) "a fresh nonce for every value")
      (is (= s (at-rest/seal s)) "a sealed value is not sealed again")
      (is (= the-token (at-rest/open s)))
      (is (true? (:encrypted (at-rest/note))))
      (testing "a value that does not verify fails loudly"
        (let [cut (subs s 0 (- (count s) 4))
              tampered (str cut (if (str/ends-with? s "AAAA") "BBBB" "AAAA"))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not verify"
                                (at-rest/open tampered)))))
      (testing "another key fails loudly"
        (with-key k2
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sealed under key"
                                (at-rest/open s)))))
      (testing "no key fails loudly, never as an empty value"
        (with-key nil
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no WAYMARK10_DATA_KEY"
                                (at-rest/open s)))))))
  (testing "no key: plaintext in, plaintext out, and said"
    (with-key nil
      (is (= the-token (at-rest/seal the-token)))
      (is (= the-token (at-rest/open the-token)))
      (is (= "d" (at-rest/keyed-digest "d")))
      (is (false? (:encrypted (at-rest/note))))))
  (testing "a malformed key refuses rather than reading as none"
    (is (thrown? clojure.lang.ExceptionInfo (at-rest/parse-key "c2hvcnQ=")))))

(deftest the-mark-is-a-secret-fields-alone
  (is (thrown-with-msg? Exception #"is :at-rest :encrypted and not :secret"
                        (r/resource (assoc-in vault-decl [:schema 2 1]
                                              {:optional true :at-rest :encrypted}))))
  (is (thrown-with-msg? Exception #"the one disposition is :encrypted"
                        (r/resource (assoc-in vault-decl [:schema 2 1 :at-rest] :hashed)))))

;; ── through the store ───────────────────────────────────────────────

(deftest a-declared-field-is-sealed-in-the-database
  (fresh!)
  (with-key k1
    (with-eng
      (fn [eng]
        (let [id (a-vault! eng)]
          (is (str/starts-with? (str (raw eng "ar_vaults" id "token")) "enc:v1:"))
          (is (= the-token (get-in (stored eng id) [:data :token])))
          (testing "the input digest is keyed and the log holds no value"
            (let [log (store/with-tx (:storage eng)
                        (fn [tx] (store/transitions (:storage eng) tx
                                                    {:kind :ar_vault :resource-id id} {})))]
              (is (seq log))
              (is (every? #(str/starts-with? (str (:input-digest %)) "hmac:") log))
              (is (not (str/includes? (pr-str log) the-token)))))
          (testing "another key fails loudly"
            (with-key k2
              (is (thrown? clojure.lang.ExceptionInfo (stored eng id))))))))))

(deftest no-key-keeps-plaintext-and-the-backfill-seals-it-once
  (fresh!)
  (let [id (with-key nil
             (with-eng
               (fn [eng]
                 (let [id (a-vault! eng)]
                   (is (= the-token (raw eng "ar_vaults" id "token"))
                       "plaintext while no key is set")
                   id))))]
    (with-key k1
      ;; the boot is the backfill
      (with-eng
        (fn [eng]
          (let [once (str (raw eng "ar_vaults" id "token"))]
            (is (str/starts-with? once "enc:v1:"))
            (is (= the-token (get-in (stored eng id) [:data :token])))
            (store/ensure-kind! (:storage eng) (get (inv/resources eng) :ar_vault))
            (is (= once (raw eng "ar_vaults" id "token"))
                "a second pass leaves a sealed value as it is")))))))

(deftest resolve-refs-forwards-the-plaintext
  (fresh!)
  (with-key k1
    (with-eng
      (fn [eng]
        (let [id (str (:id (:row (inv/create! eng :secret
                                               {:name "AR_TOKEN" :value the-token}
                                               {:principal colton}))))]
          (is (str/starts-with? (str (raw eng "secrets" id "value")) "enc:v1:"))
          (is (= {:token the-token}
                 (:args (secrets/resolve-refs!
                         eng {:properties {:token {:x-secret-ref true}}}
                         {:token id})))))))))
