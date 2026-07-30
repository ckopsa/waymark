(ns waymark10.fingerprint-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [waymark10.fingerprint :as fp]
            [waymark10.gen-forms :as gf]))

(def trials 200)

;; ── fixtures ────────────────────────────────────────────────────────

(defn plan-rmap
  "A trimmed plan declaration; vary the leaves under test."
  [& {:keys [end-days gate handler explain]
      :or {end-days 13
           gate '(not (data :has_conflicts))
           explain "the last day"}}]
  {:kind :plan
   :states [:draft :planned :active :done :abandoned]
   :initial :draft
   :terminal #{:done :abandoned}
   :actions {:finalize {:from #{:draft} :to :planned
                        :safety {:idempotent true :reversible true :confirm false}
                        :guards [{:name :calendar-clear
                                  :severity :warning
                                  :when gate
                                  :explain "{n} conflict(s) overlap this week."
                                  :vars {:n '(data :calendar_conflicts)}
                                  :remedies [:plan/clear_day]}]
                        :handler handler}
             :abandon {:from #{:draft :planned :active} :to :abandoned
                       :safety {:idempotent true :reversible false :confirm true}
                       :guards []}}
   :derived {:end_date {:over [:start_date :weeks]
                        :expr (list '+ '(var :start_date) (list 'days end-days))
                        :explain explain}
             :all_days_covered {:over [:days]
                                :expr '(every [d (var :days)]
                                         (or (is-set (get d :meal_id))
                                             (= (get d :eating_out) true)))}}})

(defn- respell
  "A meaning-preserving re-spelling: flip orderings, reverse equality
  operands, duplicate a conjunct. Normalization must erase all of it."
  [form]
  (if (seq? form)
    (let [[op & args] form
          args (map respell args)]
      (case op
        <   (list '> (second args) (first args))
        <=  (list '>= (second args) (first args))
        =   (cons '= (reverse args))
        and (concat (cons 'and args) [(first args)])
        (cons op args)))
    form))

;; ── properties ──────────────────────────────────────────────────────

(defspec respelling-a-law-is-not-a-revision trials
  (prop/for-all [f gf/gen-form]
    (= (fp/fingerprint-hash (fp/fingerprint-of (plan-rmap :gate f)))
       (fp/fingerprint-hash (fp/fingerprint-of (plan-rmap :gate (respell f)))))))

(defspec a-diff-confined-to-a-derived-expr-leaf-is-data-law trials
  (prop/for-all [days-a (clojure.test.check.generators/choose 1 200)
                 days-b (clojure.test.check.generators/choose 201 400)]
    (let [d (fp/diff-fingerprints
             (fp/fingerprint-of (plan-rmap :end-days days-a))
             (fp/fingerprint-of (plan-rmap :end-days days-b)))]
      (and (= :data-law (fp/classify-diff d))
           (= ["end_date"] (fp/stale-facts d))))))

;; ── units ───────────────────────────────────────────────────────────

(deftest identical-declarations-produce-identical-hashes
  (is (= (fp/fingerprint-hash (fp/fingerprint-of (plan-rmap)))
         (fp/fingerprint-hash (fp/fingerprint-of (plan-rmap))))))

(deftest views-are-advertisement-never-law
  ;; :views is presentation — advertising, renaming, or dropping a
  ;; collection view must not mint a revision
  (is (= (fp/fingerprint-hash (fp/fingerprint-of (plan-rmap)))
         (fp/fingerprint-hash
          (fp/fingerprint-of
           (assoc (plan-rmap)
                  :views [{:name :triage :kind :deck
                           :where {:state "draft"}
                           :right :finalize :left :abandon
                           :display {:label "Triage"}}]))))))

(deftest a-fingerprint-diffs-empty-against-itself
  ;; regression: leaves holding nil/false (a guard's check, an off
  ;; safety flag) are present paths, not added/removed ones
  (let [d (fp/diff-fingerprints (fp/fingerprint-of (plan-rmap))
                                (fp/fingerprint-of (plan-rmap)))]
    (is (= {:added [] :removed [] :changed []} d))))

(deftest the-diff-pins-the-moved-leaf
  (let [d (fp/diff-fingerprints (fp/fingerprint-of (plan-rmap :end-days 13))
                                (fp/fingerprint-of (plan-rmap :end-days 14)))]
    (is (= ["derived.end_date.expr.1.1"] (mapv :path (:changed d))))
    (is (empty? (:added d)))
    (is (empty? (:removed d)))))

(deftest judgment-leaves-are-data-law
  (testing "a gate's expression tree"
    (let [d (fp/diff-fingerprints
             (fp/fingerprint-of (plan-rmap :gate '(not (data :has_conflicts))))
             (fp/fingerprint-of (plan-rmap :gate '(<= (data :calendar_conflicts) 1))))]
      (is (= :data-law (fp/classify-diff d)))
      (is (empty? (fp/stale-facts d)) "a gate change flips no stored value")))
  (testing "a gate's severity"
    (let [strict (update-in (plan-rmap) [:actions :finalize :guards 0]
                            assoc :severity :refuse)
          d (fp/diff-fingerprints (fp/fingerprint-of (plan-rmap))
                                  (fp/fingerprint-of strict))]
      (is (= ["machine.actions.finalize.guards.0.severity"]
             (mapv :path (:changed d))))
      (is (= :data-law (fp/classify-diff d))))))

(deftest code-and-shape-promote-totally
  (testing "handler identity"
    (is (= :code-or-shape
           (fp/classify-diff
            (fp/diff-fingerprints (fp/fingerprint-of (plan-rmap))
                                  (fp/fingerprint-of (plan-rmap :handler "deadbeef")))))))
  (testing "machine shape: a state added"
    (is (= :code-or-shape
           (fp/classify-diff
            (fp/diff-fingerprints
             (fp/fingerprint-of (plan-rmap))
             (fp/fingerprint-of (update (plan-rmap) :states conj :archived)))))))
  (testing "a guard added shifts positional paths and refuses"
    (is (= :code-or-shape
           (fp/classify-diff
            (fp/diff-fingerprints
             (fp/fingerprint-of (plan-rmap))
             (fp/fingerprint-of
              (update-in (plan-rmap) [:actions :finalize :guards]
                         conj {:name :inert :when '(= 1 1) :explain "x"})))))))
  (testing "derived explain is advertisement, and not overlayable"
    (let [d (fp/diff-fingerprints
             (fp/fingerprint-of (plan-rmap))
             (fp/fingerprint-of (plan-rmap :explain "the final day")))]
      (is (= [:advertisement] (mapv :class (:changed d))))
      (is (= :code-or-shape (fp/classify-diff d))
          "render reads explain from resident objects; a hold would lie")
      (is (empty? (fp/stale-facts d))))))

(deftest classify-path-orders-its-families
  (is (= :judgment (fp/classify-path "machine.actions.finalize.guards.0.explain"))
      "the innermost owning surface wins: a guard's explain is judgment, not machine")
  (is (= :advertisement (fp/classify-path "derived.end_date.explain")))
  (is (= :advertisement (fp/classify-path "derived.end_date.vars.n.0")))
  (is (= :truth (fp/classify-path "derived.end_date.expr.1.1")))
  (is (= :shape (fp/classify-path "storage.columns.0.name")))
  (is (= :judgment (fp/classify-path "machine.actions.finalize.safety.confirm")))
  (is (= :truth (fp/classify-path "somewhere.unmapped"))
      "an unclassified change is conservatively a change of meaning"))
