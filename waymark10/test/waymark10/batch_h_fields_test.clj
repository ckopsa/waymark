(ns waymark10.batch-h-fields-test
  "Batch H, deltas 1, 2 and 6 — the typed field words and the :fields
  lifecycle groups. Words return the exact malli forms the inline
  spelling writes (entry properties as metadata the :fields reader
  hoists); the groups normalize into :schema + :create-schema +
  generated editors + the :when create gates; measured-by lands the
  closest check-based equivalent of the sibling-dispatched union on
  the group's editor. The invariance proof pins the sugared and split
  spellings to one normalized map and one fingerprint."
  (:refer-clojure :exclude [ref])
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.declare :as d
             :refer [date flag measured-by money one-of percent prose
                     quantity ref]]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

;; ── delta 1: the words are the inline forms ─────────────────────────

(deftest words-return-the-exact-malli-forms
  (is (= [:enum "dollars" "shares" "pct"] (one-of :dollars :shares :pct)))
  (is (= :waymark/date (date)))
  (is (= :boolean (flag)))
  (is (= [:decimal {:min 0}] (quantity)))
  (is (= :decimal (r/word-form (money :usd))))
  (is (= [:decimal {:min 0 :max 100}] (r/word-form (percent))))
  (is (= [:string {:min 1 :max 8000}] (r/word-form (prose))))
  (is (= :waymark/ref (r/word-form (ref :fund))))
  (is (= :decimal (r/word-form (measured-by :value_type
                                            {:dollars (money :usd)
                                             :pct (percent)})))))

(deftest words-carry-their-properties-as-metadata
  (is (= {:kind :fund} (r/word-props (ref :fund))))
  (is (= {:kind :fund :label :fund_name}
         (r/word-props (ref :fund {:label :fund_name}))))
  (is (= {:kind :chore :label :chore_name :carry {:notes :chore_notes}}
         (r/word-props (ref :chore {:label :chore_name
                                    :carry {:notes :chore_notes}})))
      ":carry rides the ref word's props like :label does")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source-field target-field"
                        (ref :chore {:carry {:notes "chore_notes"}})))
  (is (= {:x-display {:widget "money" :currency "usd"}}
         (r/word-props (money :usd))))
  (is (= {:x-display {:widget "prose" :label "Why"}}
         (r/word-props (prose "Why"))))
  (is (= {:draft {:shared true :live true}}
         (r/word-edit (prose {:shared true :live true}))))
  (is (nil? (r/word-props (one-of :a :b)))
      "a plain word carries nothing — the form is the whole spelling"))

(deftest words-validate-at-the-call-site
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two or more keyword values"
                        (one-of :only)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"currency as a keyword"
                        (money "usd")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"prose options are"
                        (prose {:shraed true})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target kind as a keyword"
                        (ref "fund")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exact-decimal family"
                        (measured-by :t {:a (date)}))))

;; ── the :decimal schema type — exact, never a float ─────────────────

(deftest decimal-is-exact
  (is (schema/validate :decimal 0.02M))
  (is (not (schema/validate :decimal 0.02))
      "a double is refused — the E.lit(\"0.02\") lesson")
  (is (not (schema/validate :decimal 2)))
  (is (= 0.02M (schema/decode :decimal 0.02M)))
  (is (= 25000.50M (schema/decode :decimal "25000.50"))
      "wire strings decode exactly")
  (is (= 100M (schema/decode :decimal 100))
      "wire integers decode exactly")
  (is (schema/validate [:decimal {:min 0 :max 100}] 12.5M))
  (is (not (schema/validate [:decimal {:min 0 :max 100}] 250M))))

;; ── the invariance proof: one small kind, both spellings ────────────

(def sugared
  {:kind :order
   :states [:draft :placed :void]
   :initial :draft
   :terminal #{:void}
   :summary "{data.sku} · {state}"
   :fields {:at-create [[:sku (one-of :basic :custom)]
                        [:catalog (ref :catalog)]]
            :while-open [[:value_type (one-of :dollars :pct)]
                         [:amount (measured-by :value_type
                                               {:dollars (money :usd)
                                                :pct (percent)})]
                         [:ship_on (date)]
                         [:rush (flag)]]
            :support [[:comments (prose {:shared true :live true})]]
            :when {:custom [[:spec_notes (prose "Specification")]]}}
   :actions {:place {:from #{:draft} :to :placed
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Placing is the point."}}
             :void {:from #{:draft :placed} :to :void
                    :safety {:idempotent true :reversible false :confirm true
                             :consequence "The order is discarded."}}}})

(def measured-amount
  (r/measured-guard {:field :amount :by :value_type
                     :arms {"dollars" :decimal
                            "pct" [:decimal {:min 0 :max 100}]}}))

(def split
  {:kind :order
   :states [:draft :placed :void]
   :initial :draft
   :terminal #{:void}
   :summary "{data.sku} · {state}"
   :schema [:map
            [:sku [:enum "basic" "custom"]]
            [:catalog {:kind :catalog} :waymark/ref]
            [:value_type {:optional true} [:maybe [:enum "dollars" "pct"]]]
            [:amount {:optional true
                      :x-display {:widget "measured"
                                  :measured-by {:by "value_type"
                                                :arms {"dollars" {:widget "money"
                                                                  :currency "usd"}
                                                       "pct" {:widget "percent"}}}}}
             [:maybe :decimal]]
            [:ship_on {:optional true} [:maybe :waymark/date]]
            [:rush {:optional true} [:maybe :boolean]]
            [:comments {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:min 1 :max 8000}]]]
            [:spec_notes {:optional true
                          :x-display {:widget "prose" :label "Specification"}}
             [:maybe [:string {:min 1 :max 8000}]]]]
   :create-schema [:map
                   [:sku [:enum "basic" "custom"]]
                   [:catalog {:kind :catalog} :waymark/ref]
                   [:spec_notes {:optional true
                                 :x-display {:widget "prose"
                                             :label "Specification"}}
                    [:maybe [:string {:min 1 :max 8000}]]]]
   :create-guards [(g/expr {:name :spec_notes_required_for_custom
                            :when '(or (not= (input :sku) "custom")
                                       (is-set (input :spec_notes)))
                            :explain "A custom declares its spec notes at create."})]
   :actions
   {:place {:from #{:draft} :to :placed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "Placing is the point."}}
    :void {:from #{:draft :placed} :to :void
           :safety {:idempotent true :reversible false :confirm true
                    :consequence "The order is discarded."}}
    :update_fields
    {:from #{:draft} :to :draft
     :input [:map
             [:value_type {:optional true} [:maybe [:enum "dollars" "pct"]]]
             [:amount {:optional true
                       :x-display {:widget "measured"
                                   :measured-by {:by "value_type"
                                                 :arms {"dollars" {:widget "money"
                                                                   :currency "usd"}
                                                        "pct" {:widget "percent"}}}}}
              [:maybe :decimal]]
             [:ship_on {:optional true} [:maybe :waymark/date]]
             [:rush {:optional true} [:maybe :boolean]]]
     :edit {:prefill [:value_type :amount :ship_on :rush]}
     :guards [measured-amount]
     :safety {:idempotent true :reversible false :confirm false}
     :handler (r/field-writer [:value_type :amount :ship_on :rush])
     :display {:label "Update fields"}}
    :update_support_in_draft
    {:from #{:draft} :to :draft
     :input [:map [:comments {:optional true :x-display {:widget "prose"}}
                   [:maybe [:string {:min 1 :max 8000}]]]]
     :edit {:prefill [:comments] :draft {:shared true :live true}}
     :guards []
     :safety {:idempotent true :reversible false :confirm false}
     :handler (r/field-writer [:comments])
     :display {:label "Update support"}}
    :update_support_in_placed
    {:from #{:placed} :to :placed
     :input [:map [:comments {:optional true :x-display {:widget "prose"}}
                   [:maybe [:string {:min 1 :max 8000}]]]]
     :edit {:prefill [:comments] :draft {:shared true :live true}}
     :guards []
     :safety {:idempotent true :reversible false :confirm false}
     :handler (r/field-writer [:comments])
     :display {:label "Update support"}}}})

(deftest two-spellings-one-law
  (is (= (r/normalize-resource split) (r/normalize-resource sugared))
      "the groups flatten to the split spelling's schema, editors and gates")
  (is (= (fp/fingerprint-hash (r/fingerprint (r/normalize-resource split)))
         (fp/fingerprint-hash (r/fingerprint (r/normalize-resource sugared))))))

(deftest editors-follow-the-lifecycle
  (let [n (r/normalize-resource sugared)]
    (testing "while-open edits in the open states only (default: initial)"
      (is (contains? (:actions n) :update_fields))
      (is (= #{:draft} (get-in n [:actions :update_fields :from]))))
    (testing "support edits in every non-terminal state"
      (is (contains? (:actions n) :update_support_in_draft))
      (is (contains? (:actions n) :update_support_in_placed))
      (is (not (contains? (:actions n) :update_support_in_void))))
    (testing "the prose draft policy rides the support editor"
      (is (= {:shared true :live true}
             (get-in n [:actions :update_support_in_draft :edit :draft]))))
    (testing "at-create fields are create input only — no editor writes them"
      (doseq [[_ a] (:actions n)
              :when (:edit a)
              f (get-in a [:edit :prefill])]
        (is (not (#{:sku :catalog} f)))))))

(deftest fields-refuses-what-it-cannot-mean
  (letfn [(err? [rmap re]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo re
                                  (r/normalize-resource rmap))))]
    (testing "one home: :fields and :schema together"
      (err? (assoc sugared :schema [:map [:x :int]])
            #"groups ARE the schema"))
    (testing "unknown groups"
      (err? (assoc-in sugared [:fields :while-closed] [])
            #"unknown group"))
    (testing "open states must be declared and non-terminal"
      (err? (assoc-in sugared [:fields :open] #{:shipping})
            #"not declared states")
      (err? (assoc-in sugared [:fields :open] #{:void})
            #"terminal"))
    (testing "a measured field's measure lives in the same group"
      (err? (update-in sugared [:fields :while-open] subvec 1)
            #"not a field of the same group"))
    (testing ":when needs its discriminating one-of"
      (err? (assoc-in sugared [:fields :when] {:bespoke [[:notes (prose)]]})
            #"no :at-create one-of field offers"))
    (testing "a non-count derived fact declares its own entry"
      (err? (assoc sugared :derived {:mystery {:over [:rush]
                                               :expr '(= (var :rush) true)}})
            #"count facts\s+only"))
    (testing "a count fact's :int entry is appended for free"
      (let [n (r/normalize-resource
               (assoc sugared
                      :owns [{:kind :order_line :via :order_id}]
                      :derived {:lines {:count {:owns :order_line}}}))]
        (is (= [:lines {:optional true} [:maybe :int]]
               (last (:schema n))))))))

;; ── the :facts group: engine-maintained entries (chore_run's
;;    clock-flipped overdue demanded it) ──────────────────────────────

(deftest facts-are-engine-maintained-entries
  (let [n (r/normalize-resource
           (-> sugared
               (assoc-in [:fields :facts] [[:overdue :boolean]])
               (assoc :derived
                      {:overdue {:over [:ship_on :now]
                                 :expr '(< (var :ship_on)
                                           (date-of (var :now)))}})))]
    (testing "the entry lands optional and nullable"
      (is (some #{[:overdue {:optional true} [:maybe :boolean]]}
                (rest (:schema n)))))
    (testing "no generated editor writes it"
      (doseq [[_ a] (:actions n)
              :when (:edit a)
              f (get-in a [:edit :prefill])]
        (is (not= :overdue f))))
    (testing "the create schema never asks for it"
      (is (not-any? #(= :overdue (first %)) (rest (:create-schema n))))))
  (testing "a fact without its :derived law refuses — one fact, one
            writer, and the writer here is the engine"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :derived law"
                          (r/normalize-resource
                           (assoc-in sugared [:fields :facts]
                                     [[:overdue :boolean]]))))))

;; ── delta 2: measured-by rejects the mismatched amount ──────────────

(defn- with-order-engine [f]
  (let [eng (inv/engine {:storage (memory/storage)
                         :resources [(r/resource sugared)]})]
    (f eng)))

(def colton (t/principal {:id "colton"}))

(deftest measured-by-rejects-mismatched-amounts
  (with-order-engine
    (fn [eng]
      (let [{row :row} (inv/create! eng :order
                                    {:sku "basic" :catalog "cat-1"}
                                    {:principal colton})
            id (:id row)]
        (testing "a pct amount over 100 refuses under value_type pct"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"not a valid pct amount"
               (inv/invoke! eng :order id :update_fields
                            {:value_type "pct" :amount 250}
                            {:principal colton
                             :if-match (inv/etag :order id 1)}))))
        (testing "the same 250 under dollars is a fine amount"
          (let [res (inv/invoke! eng :order id :update_fields
                                 {:value_type "dollars" :amount 250}
                                 {:principal colton
                                  :if-match (inv/etag :order id 1)})]
            (is (= 250M (get-in res [:row :data :amount])))))
        (testing "an amount with no measure at all refuses"
          (let [{other :row} (inv/create! eng :order
                                          {:sku "basic" :catalog "cat-1"}
                                          {:principal colton})]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo #"not a valid \(unset\) amount"
                 (inv/invoke! eng :order (:id other) :update_fields
                              {:amount 10}
                              {:principal colton
                               :if-match (inv/etag :order (:id other) 1)})))))))))

;; ── the :when gate fires only for the matching value ────────────────

(deftest conditional-required-fires-only-for-the-matching-type
  (with-order-engine
    (fn [eng]
      (testing "a custom order without spec notes refuses at create"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"custom declares its spec notes"
             (inv/create! eng :order {:sku "custom" :catalog "cat-1"}
                          {:principal colton}))))
      (testing "a custom order with them is born"
        (is (some? (:row (inv/create! eng :order
                                      {:sku "custom" :catalog "cat-1"
                                       :spec_notes "Two of everything."}
                                      {:principal colton})))))
      (testing "a basic order never needed them"
        (is (some? (:row (inv/create! eng :order
                                      {:sku "basic" :catalog "cat-1"}
                                      {:principal colton}))))))))
