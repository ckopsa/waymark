(ns waymark10.checks-test
  "One deliberately-broken declaration per check, refused by name, plus
  the green acceptance of the real fixtures. Every break is minimal:
  one thing changed from a valid base."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.types :as t]))

;; ── the valid base and helpers ──────────────────────────────────────

(def base
  {:kind :thing
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.name} · {state}"
   :schema [:map [:name [:string {:max 100}]]]
   :actions {:close {:from #{:open} :to :closed
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Closing is cheap."}}}})

(def close-action (get-in base [:actions :close]))

(defn- with-action [m aname a] (assoc-in m [:actions aname] a))

(defn- load-quietly
  "resource, with usability warnings kept off the test output."
  [m]
  (binding [*err* (java.io.StringWriter.)]
    (r/resource m)))

(defn- warnings-of [m] (:waymark10/warnings (meta (load-quietly m))))

(defn- breaks
  "Assert the declaration is refused by the named check."
  [check m]
  (try
    (load-quietly m)
    (is false (str "expected [" (name check) "] to refuse this declaration"))
    (catch clojure.lang.ExceptionInfo e
      (is (= check (:check (ex-data e))) (ex-message e)))))

(defn- warns [substr m]
  (let [ws (warnings-of m)]
    (is (some #(str/includes? % substr) ws)
        (str "expected a warning containing " (pr-str substr) " in " (pr-str ws)))))

;; guards the breaks lean on
(def judge-x
  (g/guard {:name :judge-x :judges [:x]
            :explain "{x} is not right."
            :check (fn [_ _ _] (t/allow))}))

(def judge-name
  (g/guard {:name :judge-name :judges [:name]
            :explain "{name} is not allowed."
            :check (fn [_ _ _] (t/allow))}))

(def mystery
  (g/guard {:name :mystery :judges [:name]
            :explain "{mystery} happened."
            :check (fn [_ _ _] (t/allow))}))

(def key-listed
  (g/guard {:name :key-listed :judges [:key]
            :accepts (fn [row] (mapv :key (get-in row [:data :parts])))
            :explain "{key} is not a part."}))

;; ── the base is green ───────────────────────────────────────────────

(deftest the-base-declaration-is-green
  (is (= [] (warnings-of base))))

;; ── one break per check, refused by name ────────────────────────────

(deftest tokens
  (breaks :tokens (assoc base :initial :nope)))

(deftest reachability
  (breaks :reachability (update base :states conj :limbo)))

(deftest terminal-no-exit
  (breaks :terminal-no-exit
          (with-action base :reopen
            {:from #{:closed} :to :open
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Reopening is cheap."}})))

(deftest reversible
  (breaks :reversible
          (with-action base :close
            (assoc close-action
                   :safety {:idempotent true :reversible true :confirm false}))))

(deftest one-way
  ;; the door must open from a non-initial state: leaving the initial
  ;; state closes on nothing, and self-loops re-do themselves
  (breaks :one-way
          (-> base
              (assoc :states [:open :mid :closed])
              (assoc :actions
                     {:advance {:from #{:open} :to :mid
                                :safety {:idempotent true :reversible false
                                         :confirm false
                                         :one-way "Advancing is cheap."}}
                      :finish {:from #{:mid} :to :closed
                               :safety {:idempotent true :reversible false
                                        :confirm false}}}))))

(deftest guard-declarations
  (breaks :guard-declarations
          (with-action base :close (assoc close-action :guards [judge-x]))))

(deftest guard-templates
  (breaks :guard-templates
          (with-action base :close
            (assoc close-action
                   :input [:map [:name [:string {:max 100}]]]
                   :guards [mystery]))))

(deftest create-guards
  (breaks :create-guards (assoc base :create-guards [judge-x])))

(deftest closure
  (breaks :closure
          (with-action base :close
            (assoc close-action
                   :input [:map [:name :string]]
                   :guards [judge-name]))))

(deftest handler-signatures
  (breaks :handler-signatures
          (with-action base :close (assoc close-action :handler "not-a-fn"))))

(deftest summary-template
  (breaks :summary-template (assoc base :summary "{bogus} thing")))

(deftest waive-tokens
  (breaks :waive-tokens
          (with-action base :close (assoc close-action :waives #{:speed}))))

(deftest place
  (breaks :place
          (with-action base :close
            (assoc close-action
                   :place :parts
                   :input [:map [:key [:string {:max 20}]]]))))

(deftest edit
  (breaks :edit
          (with-action base :close
            (assoc close-action
                   :edit {:prefill [:nope]}
                   :input [:map [:name [:string {:max 100}]]]))))

(deftest faceted
  (breaks :faceted (assoc base :faceted [:name])))

(deftest oneof
  (breaks :oneof (assoc base :one-of {:naming {:arms {:a [:name] :b [:name]}}})))

(deftest unique
  (breaks :unique (assoc base :unique [:name])))

(deftest links
  (breaks :links (assoc base :links [{:rel :self :badge :nope}])))

(deftest derived
  (breaks :derived
          (assoc base :derived {:score {:over [:nope] :expr '(var :nope)}})))

(deftest unless
  (breaks :unless
          (with-action base :close (assoc close-action :unless :nonexistent))))

(deftest require-check
  (testing "a fact nobody derives"
    (breaks :require
            (with-action base :close
              (assoc close-action :guards [(g/require :blessed)]))))
  (testing "a non-bool derivation is not a gate"
    (breaks :require
            (-> base
                (assoc :schema [:map [:name [:string {:max 100}]] [:score :int]]
                       :derived {:score {:over [:name]
                                         :expr '(is-set (var :name))}})
                (with-action :close
                  (assoc close-action :guards [(g/require :score)]))))))

;; ── the warnings ────────────────────────────────────────────────────

(deftest handler-without-form-metadata-warns
  (warns "no stateable identity — declare it with defhandler"
         (with-action base :close
           (assoc close-action :handler (fn [row _ _] row)))))

(deftest edit-shaped-warns
  (warns "edit-shaped"
         (with-action base :touch
           {:from #{:open} :to :open
            :input [:map [:name [:string {:max 100}]]]
            :safety {:idempotent true :reversible false :confirm false}})))

(deftest prose-required-warns
  (warns "demands composition with no draft"
         (with-action base :annotate
           {:from #{:open} :to :open
            :input [:map [:body {:x-display {:widget "prose"}}
                          [:string {:max 8000}]]]
            :safety {:idempotent true :reversible false :confirm false}})))

(deftest altitude-warns
  (warns "re-asks the user to identify an item of data.parts"
         (-> base
             (assoc :schema [:map
                             [:name [:string {:max 100}]]
                             [:parts [:vector [:map [:key [:string {:max 20}]]]]]])
             (with-action :tag
               {:from #{:open} :to :open
                :input [:map [:key [:string {:max 20}]]]
                :guards [key-listed]
                :safety {:idempotent true :reversible false :confirm false}}))))

(deftest long-text-warns
  (warns "data.bio is a text field with no shape"
         (assoc base :schema [:map
                              [:name [:string {:max 100}]]
                              [:bio :string]])))

;; ── the acceptance: the real fixtures are green ─────────────────────

(deftest the-fixtures-load-with-zero-warnings
  (is (= :meal (:kind fx/meal)))
  (is (= [] (:waymark10/warnings (meta fx/meal))))
  (is (= :plan (:kind fx/plan)))
  (is (= [] (:waymark10/warnings (meta fx/plan)))))
