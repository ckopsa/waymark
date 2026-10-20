(ns waymark10.batch-h-defguard-test
  "Batch H, delta 5 — the sentence-first defguard. The def'd value is
  the PLAIN expression-guard map g/expr builds, so the sugared and
  inline spellings are one value and (through any action that cites
  them) one fingerprint. The authored conveniences — (var :fact) for
  the stored document, zero?, present?, and sentence placeholders
  landing as (data …) garnish — all desugar at the def line, before
  validation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.declare :as d :refer [defguard hold refuse warn]]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

;; ── the sugared spellings ───────────────────────────────────────────

(defguard blocking-items-reviewed
  (refuse "Every compliance-class checklist item is reviewed — {open_blocking} remain.")
  '(zero? (var :open_blocking)))

(defguard fields-complete
  (refuse "A transaction goes to review with its value type, amount, and effective date set.")
  '(present? :value_type :amount :effective_date))

(defguard all-items-reviewed
  (warn "{open_items} checklist items are not yet reviewed."
        :acknowledge-by-name)
  '(zero? (var :open_items)))

;; ── the split spellings, byte-for-byte the same law ─────────────────

(def blocking-split
  (g/expr {:name :blocking-items-reviewed
           :when '(= 0 (data :open_blocking))
           :explain "Every compliance-class checklist item is reviewed — {open_blocking} remain."
           :severity :refuse
           :vars {:open_blocking '(data :open_blocking)}}))

(def fields-split
  (g/expr {:name :fields-complete
           :when '(and (is-set (data :value_type))
                       (is-set (data :amount))
                       (is-set (data :effective_date)))
           :explain "A transaction goes to review with its value type, amount, and effective date set."
           :severity :refuse}))

(def all-items-split
  (g/expr {:name :all-items-reviewed
           :when '(= 0 (data :open_items))
           :explain "{open_items} checklist items are not yet reviewed."
           :severity :warning
           :vars {:open_items '(data :open_items)}}))

(deftest the-defd-guard-is-the-inline-guard
  (is (= blocking-split blocking-items-reviewed)
      "(var …)→(data …), zero?→(= 0 …), and the placeholder garnish land on the exact inline value")
  (is (= fields-split fields-complete)
      "present? desugars to the is-set conjunction; a placeholder-free sentence carries no vars")
  (is (= all-items-split all-items-reviewed)
      "warn is severity :warning; :acknowledge-by-name documents E1 and changes nothing"))

(deftest the-desugared-law-reads-the-document
  (testing "verdicts evaluate over the stored facts"
    (let [row (fn [data] {:data data})
          eval' (fn [guard data]
                  (first (g/evaluate guard (row data) nil (t/ctx {:mode :invoke}))))]
      (is (t/allow? (eval' blocking-items-reviewed {:open_blocking 0})))
      (is (t/deny? (eval' blocking-items-reviewed {:open_blocking 3})))
      (is (t/allow? (eval' fields-complete {:value_type "dollars"
                                            :amount 1M
                                            :effective_date "2026-07-01"})))
      (is (t/deny? (eval' fields-complete {:value_type "dollars"})))))
  (testing "the sentence renders its garnish"
    (let [[v denier] (g/evaluate blocking-items-reviewed
                                 {:data {:open_blocking 3}} nil
                                 (t/ctx {:mode :invoke}))]
      (is (= "Every compliance-class checklist item is reviewed — 3 remain."
             (g/render-reason denier v {:data {:open_blocking 3}}))))))

(deftest one-fingerprint-through-a-citing-action
  (let [kind (fn [guard]
               {:kind :ca_probe
                :states [:draft :review]
                :initial :draft
                :terminal #{:review}
                :summary "{state}"
                :schema [:map [:open_blocking {:optional true} [:maybe :int]]]
                :actions {:submit {:from #{:draft} :to :review
                                   :guards [guard]
                                   :safety {:idempotent true :reversible false
                                            :confirm false
                                            :one-way "Submitting is cheap."}}}})]
    (is (= (r/normalize-resource (kind blocking-items-reviewed))
           (r/normalize-resource (kind blocking-split))))
    (is (= (fp/fingerprint-hash (r/fingerprint (r/normalize-resource (kind blocking-items-reviewed))))
           (fp/fingerprint-hash (r/fingerprint (r/normalize-resource (kind blocking-split))))))))

;; ── the def line teaches ────────────────────────────────────────────

(deftest defguard-validates-at-the-def-site
  (testing "a guard is a verdict — a sentence alone is refused"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"give it its law"
         (d/sentence-guard :lonely (refuse "A sentence with no law.") nil))))
  (testing "the sentence clause is required"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"first argument is the sentence"
         (d/sentence-guard :bad {:oops true} '(zero? (var :n))))))
  (testing "warn accepts only :acknowledge-by-name"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"warn accepts only"
         (warn "sentence" :acknowledge-silently))))
  (testing "refuse and warn demand a sentence"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"refusal sentence"
         (refuse "   ")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"warning sentence"
         (warn ""))))
  (testing "present? takes field-name keywords"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"present\?.*field-name keywords"
         (d/sentence-guard :bad (refuse "sentence")
                           '(present? "value_type")))))
  (testing "zero? takes exactly one expression"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"zero\?.*exactly one"
         (d/sentence-guard :bad (refuse "sentence")
                           '(zero? (var :a) (var :b))))))
  (testing "a law outside the vocabulary is refused with g/expr's teaching"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not in the law's vocabulary"
         (d/sentence-guard :bad (refuse "sentence")
                           '(pushed? :beacon))))))

;; ── the refusal's own list (waymark-g4e) ────────────────────────────

(deftest listed-renders-every-offender-from-any-collection
  (testing "a vector: sorted, de-duplicated, quoted"
    (is (= "\"/api/tasks/a\", \"/api/tasks/b\""
           (g/listed ["/api/tasks/b" "/api/tasks/a" "/api/tasks/b"])))
    (is (= "\"a\", \"b\", \"c\"" (g/listed ["c" "b" "a"]))))
  (testing "A SET, which is the whole bead: clojure.core/distinct
            destructures [f :as xs] and nth is unsupported on a
            PersistentHashSet, so an author who built its offenders
            with (into #{} …) threw where it meant to refuse and the
            router answered 500 with the sentence lost"
    (is (= "\"a\", \"b\"" (g/listed #{"b" "a"})))
    (is (= "\"one\"" (g/listed #{"one"})))
    ;; a hash set big enough to leave the array-map/array-set path,
    ;; because the trap only shows on the collection type the caller
    ;; actually built
    (is (= (str/join ", " (map pr-str (range 0 40)))
           (g/listed (into #{} (range 0 40))))))
  (testing "nil and empty render as the empty string — a guard's
            sentence must never be the thing that fails"
    (is (= "" (g/listed nil)))
    (is (= "" (g/listed [])))
    (is (= "" (g/listed #{})))))

;; ── a person's tap, one line (ticket 914d0cff) ───────────────────────

(defguard principal-signs-off
  (hold "A study is signed off only on The Principal's tap."))

(defguard big-spends-wait
  (hold "Spending $5,000 or more waits for The Principal's tap.")
  '(< (data :amount_usd) 5000))

(def ^:private the-principal (t/principal {:id "principal" :display "The Principal"}))
(def ^:private the-clerk (t/principal {:id "study-clerk" :type :agent}))

(defn- at-the-door
  "The ctx a write on study/sign_off judges with, as invoke builds it."
  [principal & [extra]]
  (merge {:mode :invoke :principal principal
          :self {:kind :study :action :sign_off} :door :sign_off}
         extra))

(defn- replay
  "The engine's replay of a held call the person allowed: :within names
  it, and :read answers it back."
  [held]
  {:within {:kind :held_call :action :allow :id "h1"}
   :read (fn [k id] (when (and (= :held_call k) (= "h1" id)) held))})

(def ^:private allowed-sign-off
  {:state :allowed
   :data {:door {:kind "study" :action "sign_off" :id "s1"} :caller "study-clerk"}})

(defn- verdict [guard row ctx] (first (g/evaluate guard row {} ctx)))

(deftest a-hold-clause-is-a-persons-tap
  (let [row {:id "s1" :state :review :data {}}]
    (testing "it compiles to the hand-written hold's shape, and is registered"
      (is (true? (:hold principal-signs-off)))
      (is (= [:principal :within] (:reads principal-signs-off)))
      (is (holds/hold? :principal-signs-off)))
    (testing "an agent's call is held"
      (is (t/deny? (verdict principal-signs-off row (at-the-door the-clerk)))))
    (testing "a person's call goes through"
      (is (t/allow? (verdict principal-signs-off row (at-the-door the-principal)))))
    (testing "the replay of the call the person allowed goes through"
      (is (t/allow? (verdict principal-signs-off row
                             (at-the-door the-clerk (replay allowed-sign-off))))))
    (testing "a held call for another door, row or caller opens nothing"
      (doseq [h [(assoc-in allowed-sign-off [:data :door :action] "send_back")
                 (assoc-in allowed-sign-off [:data :door :id] "s2")
                 (assoc-in allowed-sign-off [:data :caller] "someone-else")
                 (assoc allowed-sign-off :state :pending)]]
        (is (t/deny? (verdict principal-signs-off row (at-the-door the-clerk (replay h)))))))))

(deftest a-hold-with-a-law-holds-only-while-the-law-is-false
  (let [small {:id "s1" :state :review :data {:amount_usd 1200}}
        big {:id "s1" :state :review :data {:amount_usd 9000}}]
    (is (holds/hold? :big-spends-wait))
    (is (t/allow? (verdict big-spends-wait small (at-the-door the-clerk))))
    (is (t/deny? (verdict big-spends-wait big (at-the-door the-clerk))))
    (is (t/allow? (verdict big-spends-wait big (at-the-door the-principal))))
    (is (t/allow? (verdict big-spends-wait big (at-the-door the-clerk (replay allowed-sign-off)))))))

(deftest a-hold-validates-at-the-def-site
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hold takes the sentence" (hold " ")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hold takes only its sentence"
                        (hold "A tap." :for :role)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hold's law"
                        (d/sentence-guard :bad (hold "A tap.") "not a law"))))
