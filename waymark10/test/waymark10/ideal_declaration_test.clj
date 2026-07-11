(ns waymark10.ideal-declaration-test
  "Batch H's acceptance: the disbursement transaction — the intake
  declaration that earned every batch-H spelling — compiles,
  normalizes, fingerprints byte-identical to its fully desugared
  spelling, boots a real engine, and walks draft → review → done over
  the wire.

  Recorded deltas from the ideal spelling (each earned its named
  demand in docs/waymark10-design.md §22):
  - four-eyes and already-pushed are OUT: sole-preparer? needs a
    query over the owned checklist items' transition logs, pushed?
    needs mirror push state — engine facts that do not exist yet;
    faking them would be worse than waiting.
  - all-items-reviewed carries its verdict expression explicitly —
    a sentence alone is not a verdict.
  - kick_back and reopen spell their :undo pointers (every flow row
    declares its safety story); kick_back adds :record true so the
    reason rides the ledger.
  - the counts' :where sets are POSITIVE state sets ({:not …} has no
    spelling), :blocking? is :blocking (a promoted column name),
    :overdue? is out (derived facts cannot read the machine state).
  - the summary reads {data.…} roots; {fund/name}-style label paths
    are unspelled.
  - :fields adds :open #{:draft :ready_for_review} — the machine
    cannot infer where authoring ends."
  (:refer-clojure :exclude [ref])
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.declare :as d
             :refer [date defguard flag measured-by money one-of percent
                     prose quantity ref refuse warn]]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.wire :as wire]))

;; ── the guards, sentence-first ──────────────────────────────────────

(defguard fields-complete
  (refuse "A transaction goes to review with its value type, amount, and effective date set.")
  '(present? :value_type :amount :effective_date))

(defguard blocking-items-reviewed
  (refuse "Every compliance-class checklist item is reviewed — {open_blocking} remain.")
  '(zero? (var :open_blocking)))

(defguard all-items-reviewed
  (warn "{open_items} checklist items are not yet reviewed."
        :acknowledge-by-name)
  '(zero? (var :open_items)))

;; ── the declaration, in the ideal spelling ──────────────────────────

(def disbursement-map
  {:kind :disbursement
   :plural "disbursements"
   :summary "{data.type} · {data.amount} · {state} · {data.prepared}/{data.total} prepared · funding {data.funding_date}"
   :initial :draft
   :terminal #{:cancelled}
   :flow
   [[:draft            :submit_for_review :ready_for_review
     {:requires [fields-complete] :undo :kick_back}]
    [:ready_for_review :kick_back         :draft
     {:args [[:reason (prose "Why it went back — rides the ledger forever.")]]
      :record true
      :undo :submit_for_review}]
    [:ready_for_review :done              :done
     {:requires [blocking-items-reviewed all-items-reviewed]
      :confirm "Completes the transaction. If this fund's book of record is Beacon, the push is queued now."
      :undo :reopen}]
    [:done             :reopen            :ready_for_review
     {:undo :done}]
    [:draft            :cancel            :cancelled
     {:confirm "Cancels this draft. Nothing has been pushed to Beacon; nothing will be. This cannot be undone."}]
    [:ready_for_review :cancel            :cancelled
     {:confirm "Discards the prepared checklist items with it."}]
    [:done             :cancel            :cancelled
     {:confirm "Cancels a COMPLETED transaction. A Beacon push is not recalled — the Beacon side must be resolved by hand."}]]
   :fields
   {:at-create
    [[:type     (one-of :initial_subscription :addition :redemption :transfer)]
     [:fund     (ref :fund)]
     [:investor (ref :investor)]]
    :while-open
    [[:value_type (one-of :dollars :shares :pct)]
     [:amount     (measured-by :value_type
                               {:dollars (money :usd)
                                :shares  (quantity)
                                :pct     (percent)})]
     [:placement_fee  (percent)]
     [:incentive_fee  (percent)]
     [:management_fee (percent)]
     [:end_of_period  (flag)]
     [:date_order_received     (date)]
     [:effective_date          (date)]
     [:funding_date            (date)]
     [:manager_acceptance_date (date)]]
    :support
    [[:outstanding_items (prose {:shared true :live true})]
     [:comments          (prose {:shared true :live true})]]
    :when {:initial_subscription
           [[:risk_rating (ref :risk_rating_matrix)]]}
    :open #{:draft :ready_for_review}}
   :owns {:checklist_items {:kind :checklist_item :on {:cancel :cancel}}}
   :derived
   {:prepared      {:count {:owns :checklist_items
                            :where {:state #{:prepared :reviewed}}}}
    :total         {:count {:owns :checklist_items}}
    :open_items    {:count {:owns :checklist_items
                            :where {:state #{:pending :prepared}}}}
    :open_blocking {:count {:owns :checklist_items
                            :where {:blocking true
                                    :state #{:pending :prepared}}}}}})

;; ── the fully desugared spelling — today's map, written by hand ─────

(def ^:private prose-entry
  [:string {:min 1 :max 8000}])

(def ^:private measured-x-display
  {:widget "measured"
   :measured-by {:by "value_type"
                 :arms {"dollars" {:widget "money" :currency "usd"}
                        "shares" {}
                        "pct" {:widget "percent"}}}})

(def ^:private while-open-entries
  [[:value_type {:optional true} [:maybe [:enum "dollars" "shares" "pct"]]]
   [:amount {:optional true :x-display measured-x-display} [:maybe :decimal]]
   [:placement_fee {:optional true :x-display {:widget "percent"}}
    [:maybe [:decimal {:min 0 :max 100}]]]
   [:incentive_fee {:optional true :x-display {:widget "percent"}}
    [:maybe [:decimal {:min 0 :max 100}]]]
   [:management_fee {:optional true :x-display {:widget "percent"}}
    [:maybe [:decimal {:min 0 :max 100}]]]
   [:end_of_period {:optional true} [:maybe :boolean]]
   [:date_order_received {:optional true} [:maybe :waymark/date]]
   [:effective_date {:optional true} [:maybe :waymark/date]]
   [:funding_date {:optional true} [:maybe :waymark/date]]
   [:manager_acceptance_date {:optional true} [:maybe :waymark/date]]])

(def ^:private support-entries
  [[:outstanding_items {:optional true :x-display {:widget "prose"}}
    [:maybe prose-entry]]
   [:comments {:optional true :x-display {:widget "prose"}}
    [:maybe prose-entry]]])

(def ^:private amount-measured
  (r/measured-guard {:field :amount :by :value_type
                     :arms {"dollars" :decimal
                            "shares" [:decimal {:min 0}]
                            "pct" [:decimal {:min 0 :max 100}]}}))

(def ^:private while-open-fields
  [:value_type :amount :placement_fee :incentive_fee :management_fee
   :end_of_period :date_order_received :effective_date :funding_date
   :manager_acceptance_date])

(defn- update-fields-action [state]
  {:from #{state} :to state
   :input (into [:map] while-open-entries)
   :edit {:prefill while-open-fields}
   :guards [amount-measured]
   :safety {:idempotent true :reversible false :confirm false}
   :handler (r/field-writer while-open-fields)
   :display {:label "Update fields"}})

(defn- update-support-action [state]
  {:from #{state} :to state
   :input (into [:map] support-entries)
   :edit {:prefill [:outstanding_items :comments]
          :draft {:shared true :live true}}
   :guards []
   :safety {:idempotent true :reversible false :confirm false}
   :handler (r/field-writer [:outstanding_items :comments])
   :display {:label "Update support"}})

(def disbursement-split
  {:kind :disbursement
   :plural "disbursements"
   :states [:draft :ready_for_review :done :cancelled]
   :initial :draft
   :terminal #{:cancelled}
   :summary "{data.type} · {data.amount} · {state} · {data.prepared}/{data.total} prepared · funding {data.funding_date}"
   :schema (-> [:map
                [:type [:enum "initial_subscription" "addition" "redemption"
                        "transfer"]]
                [:fund {:kind :fund} :waymark/ref]
                [:investor {:kind :investor} :waymark/ref]]
               (into while-open-entries)
               (into support-entries)
               (conj [:risk_rating {:optional true :kind :risk_rating_matrix}
                      [:maybe :waymark/ref]])
               ;; the count facts' entries, fact-name order
               (conj [:open_blocking {:optional true} [:maybe :int]])
               (conj [:open_items {:optional true} [:maybe :int]])
               (conj [:prepared {:optional true} [:maybe :int]])
               (conj [:total {:optional true} [:maybe :int]]))
   :create-schema [:map
                   [:type [:enum "initial_subscription" "addition" "redemption"
                           "transfer"]]
                   [:fund {:kind :fund} :waymark/ref]
                   [:investor {:kind :investor} :waymark/ref]
                   [:risk_rating {:optional true :kind :risk_rating_matrix}
                    [:maybe :waymark/ref]]]
   :create-guards [(g/expr {:name :risk_rating_required_for_initial_subscription
                            :when '(or (not= (input :type) "initial_subscription")
                                       (is-set (input :risk_rating)))
                            :explain "A initial subscription declares its risk rating at create."})]
   :owns [{:kind :checklist_item :via :disbursement_id
           :on {:cancel :cancel}}]
   :derived
   {:prepared      {:count {:owns :checklist_item
                            :where {:state #{"prepared" "reviewed"}}}}
    :total         {:count {:owns :checklist_item}}
    :open_items    {:count {:owns :checklist_item
                            :where {:state #{"pending" "prepared"}}}}
    :open_blocking {:count {:owns :checklist_item
                            :where {:blocking #{true}
                                    :state #{"pending" "prepared"}}}}}
   :actions
   {:submit_for_review
    {:from #{:draft} :to :ready_for_review
     :guards [(g/expr {:name :fields-complete
                       :when '(and (is-set (data :value_type))
                                   (is-set (data :amount))
                                   (is-set (data :effective_date)))
                       :explain "A transaction goes to review with its value type, amount, and effective date set."
                       :severity :refuse})]
     :safety {:idempotent true :reversible true :confirm false}}
    :kick_back
    {:from #{:ready_for_review} :to :draft
     :input [:map [:reason {:x-display {:widget "prose"
                                        :label "Why it went back — rides the ledger forever."}}
                   prose-entry]]
     :record true
     :safety {:idempotent true :reversible true :confirm false}}
    :done
    {:from #{:ready_for_review} :to :done
     :guards [(g/expr {:name :blocking-items-reviewed
                       :when '(= 0 (data :open_blocking))
                       :explain "Every compliance-class checklist item is reviewed — {open_blocking} remain."
                       :severity :refuse
                       :vars {:open_blocking '(data :open_blocking)}})
              (g/expr {:name :all-items-reviewed
                       :when '(= 0 (data :open_items))
                       :explain "{open_items} checklist items are not yet reviewed."
                       :severity :warning
                       :vars {:open_items '(data :open_items)}})]
     :safety {:idempotent true :reversible true :confirm true
              :consequence "Completes the transaction. If this fund's book of record is Beacon, the push is queued now."}}
    :reopen
    {:from #{:done} :to :ready_for_review
     :safety {:idempotent true :reversible true :confirm false}}
    :cancel
    {:from #{:draft :ready_for_review :done} :to :cancelled
     :safety {:idempotent true :reversible false :confirm true
              :consequence {:draft "Cancels this draft. Nothing has been pushed to Beacon; nothing will be. This cannot be undone."
                            :ready_for_review "Discards the prepared checklist items with it."
                            :done "Cancels a COMPLETED transaction. A Beacon push is not recalled — the Beacon side must be resolved by hand."}}}
    :update_fields_in_draft (update-fields-action :draft)
    :update_fields_in_ready_for_review (update-fields-action :ready_for_review)
    :update_support_in_draft (update-support-action :draft)
    :update_support_in_ready_for_review (update-support-action :ready_for_review)
    :update_support_in_done (update-support-action :done)}})

;; ── the invariance proof ────────────────────────────────────────────

(deftest the-ideal-and-the-desugared-spelling-are-one-law
  (is (= (r/normalize-resource disbursement-split)
         (r/normalize-resource disbursement-map))
      "one normalized map — the fingerprint has nothing to tell apart")
  (is (= (fp/fingerprint-hash (r/fingerprint
                               (r/normalize-resource disbursement-split)))
         (fp/fingerprint-hash (r/fingerprint
                               (r/normalize-resource disbursement-map))))
      "byte-identical hashes"))

;; ── the partner kinds (plain house spellings — not under proof) ─────

(def checklist-item
  (r/resource
   {:kind :checklist_item
    :states [:pending :prepared :reviewed :cancelled]
    :initial :pending
    :terminal #{:cancelled}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 200}]]
             [:blocking {:optional true :filter #{:eq}} [:maybe :boolean]]
             [:disbursement_id {:kind :disbursement :filter #{:eq}}
              :waymark/ref]]
    :actions
    {:prepare {:from #{:pending} :to :prepared
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Preparation is progress; review follows."}}
     :review {:from #{:prepared} :to :reviewed
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A reviewed item is the reviewer's word."}}
     :cancel {:from #{:pending :prepared :reviewed} :to :cancelled
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Cancelled with its transaction; the item keeps its history."}}}}))

(defn- stub-kind [kind plural]
  (r/resource
   {:kind kind
    :plural plural
    :states [:active]
    :initial :active
    :terminal #{:active}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 200}]]]
    :actions {}}))

(def fund (stub-kind :fund "funds"))
(def investor (stub-kind :investor "investors"))
(def risk-rating-matrix (stub-kind :risk_rating_matrix "risk_rating_matrices"))

;; ── the engine and the wire ─────────────────────────────────────────

(def dsn
  (or (System/getenv "WAYMARK10_TEST_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_test?user=ckopsa"))

(def ^:dynamic *h* nil)
(def ^:dynamic *eng* nil)

(defn- with-handler [f]
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["disbursements" "checklist_items" "funds"
                         "investors" "risk_rating_matrices" "definitions"
                         "waymark10_transitions" "waymark10_idempotency"
                         "waymark10_drafts"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (let [eng (engine/engine {:storage st
                                :resources [(r/resource disbursement-map)
                                            checklist-item fund investor
                                            risk-rating-matrix]})]
        (binding [*eng* eng
                  *h* (engine/handler eng)]
          (f)))
      (finally (pg/close! st)))))

(use-fixtures :once with-handler)

(defn- req
  ([method uri] (req method uri nil nil))
  ([method uri body] (req method uri body nil))
  ([method uri body headers]
   (*h* (cond-> {:request-method method
                 :uri uri
                 :headers (merge {"x-waymark-principal" "colton"} headers)}
          body (assoc :body (wire/write-json body))))))

(defn- json [resp] (wire/read-json (:body resp)))

(defn- act
  ([self action] (act self action nil nil))
  ([self action body] (act self action body nil))
  ([self action body headers]
   (req :post (str self "/-/" (name action)) body headers)))

(defn- etag-of [self]
  (get-in (json (req :get self)) [:meta :etag]))

(deftest ^:integration the-disbursement-walk
  ;; the reference targets exist first — refs point at real rows
  (let [fund-self (get (json (req :post "/api/funds" {:name "Growth Fund I"}))
                       :self)
        investor-self (get (json (req :post "/api/investors" {:name "Alice"}))
                           :self)
        rrm-self (get (json (req :post "/api/risk_rating_matrices"
                                       {:name "Standard matrix"}))
                      :self)
        ref-id #(last (str/split % #"/"))
        fund-ref (ref-id fund-self)
        investor-ref (ref-id investor-self)
        rrm-ref (ref-id rrm-self)]
    (is (every? string? [fund-self investor-self rrm-self]))

    (testing "the :when gate — an initial subscription demands its risk rating"
      (let [resp (req :post "/api/disbursements"
                      {:type "initial_subscription"
                       :fund fund-ref :investor investor-ref})]
        (is (= 409 (:status resp)))
        (is (re-find #"declares its risk rating"
                     (get (json resp) :detail))))
      (let [resp (req :post "/api/disbursements"
                      {:type "addition"
                       :fund fund-ref :investor investor-ref})]
        (is (= 201 (:status resp)) "an addition never needed one")
        (is (= 200 (:status (act (get (json resp) :self) :cancel))))))

    (let [created (json (req :post "/api/disbursements"
                             {:type "initial_subscription"
                              :fund fund-ref :investor investor-ref
                              :risk_rating rrm-ref}))
          self (get created :self)]

      (testing "born in draft, counts materialized at zero"
        (is (= "draft" (get created :state)))
        (let [doc (json (req :get self))]
          (is (= 0 (get-in doc [:data :total])))
          (is (= 0 (get-in doc [:data :open_blocking])))))

      (testing "the envelope teaches: review is gated, editing is open"
        (let [doc (json (req :get self))]
          (is (contains? (get doc :unavailable) :submit_for_review))
          (is (re-find #"value type, amount, and effective date"
                       (get-in doc [:unavailable :submit_for_review :reason])))
          (is (contains? (get doc :actions) :update_fields_in_draft))
          (is (contains? (get doc :actions) :update_support_in_draft))
          (is (get-in doc [:actions :update_support_in_draft :draft :shared])
              "the support prose declares a shared live draft")
          (is (= "Cancels this draft. Nothing has been pushed to Beacon; nothing will be. This cannot be undone."
                 (get-in doc [:actions :cancel :display :description]))
              "from draft, cancel costs the draft sentence")))

      (testing "measured-by refuses a pct amount that is no percentage"
        (let [resp (act self :update_fields_in_draft
                        {:value_type "pct" :amount 250}
                        {"if-match" (etag-of self)})]
          (is (= 409 (:status resp)))
          (is (re-find #"not a valid pct amount" (get (json resp) :detail)))))

      (testing "a dollars amount lands exactly"
        (let [resp (act self :update_fields_in_draft
                        {:value_type "dollars" :amount 25000.5M
                         :effective_date "2026-07-01"
                         :funding_date "2026-07-15"}
                        {"if-match" (etag-of self)})]
          (is (= 200 (:status resp)))
          (is (= 25000.5M (get-in (json resp) [:data :amount])))))

      (testing "submit, kick back with the recorded reason, resubmit"
        (is (= 200 (:status (act self :submit_for_review))))
        (is (= "ready_for_review" (get (json (req :get self)) :state)))
        (is (= 200 (:status (act self :kick_back
                                 {:reason "Amount unverified against the subscription doc."}))))
        (is (= "draft" (get (json (req :get self)) :state)))
        (let [recorded (store/with-tx (:storage *eng*)
                         (fn [tx]
                           (store/transitions (:storage *eng*) tx
                                              {:kind :disbursement
                                               :resource-id (ref-id self)}
                                              {:newest-first true :limit 1})))
              inputs (:inputs (first recorded))]
          (is (= "Amount unverified against the subscription doc."
                 (or (get inputs :reason) (get inputs "reason")))
              ":record true — the reason rides the ledger"))
        (is (= 200 (:status (act self :submit_for_review)))))

      (testing "from review, the same cancel costs a different sentence"
        (let [doc (json (req :get self))]
          (is (= "Discards the prepared checklist items with it."
                 (get-in doc [:actions :cancel :display :description])))))

      (testing "checklist items arrive; the counts follow; done is gated"
        (let [item! (fn [title blocking]
                      (json (req :post "/api/checklist_items"
                                 {:title title :blocking blocking
                                  :disbursement_id (ref-id self)})))
              kyc (item! "KYC review" true)
              wire-conf (item! "Wire confirmation" false)
              doc (json (req :get self))]
          (is (= 2 (get-in doc [:data :total])))
          (is (= 1 (get-in doc [:data :open_blocking])))
          (is (= 2 (get-in doc [:data :open_items])))
          (is (= 0 (get-in doc [:data :prepared])))
          (let [resp (act self :done)]
            (is (= 409 (:status resp)))
            (is (re-find #"1 remain" (get (json resp) :detail))
                "the refusal counts the open blocking items"))
          ;; review the blocking item
          (is (= 200 (:status (act (get kyc :self) :prepare))))
          (is (= 200 (:status (act (get kyc :self) :review))))
          (let [doc (json (req :get self))]
            (is (= 0 (get-in doc [:data :open_blocking])))
            (is (= 1 (get-in doc [:data :prepared]))))
          ;; the advisory warning: acknowledged by name (E1)
          (let [resp (act self :done)]
            (is (= 409 (:status resp)))
            (is (= ["all-items-reviewed"]
                   (get-in (json resp) [:acknowledge :names])))
            (is (re-find #"1 checklist items are not yet reviewed"
                         (get-in (json resp) [:warnings 0 :reason]))))
          (is (= 200 (:status (act self :done nil
                                   {"waymark-acknowledge" "all-items-reviewed"}))))
          (is (= "done" (get (json (req :get self)) :state)))
          ;; the honest reverse
          (is (= 200 (:status (act self :reopen))))
          (is (= "ready_for_review" (get (json (req :get self)) :state)))
          ;; finish the second item; done no longer warns
          (is (= 200 (:status (act (get wire-conf :self) :prepare))))
          (is (= 200 (:status (act (get wire-conf :self) :review))))
          (is (= 200 (:status (act self :done))))
          (testing "from done, cancel warns of the Beacon side and cascades"
            (let [doc (json (req :get self))]
              (is (re-find #"Beacon push is not recalled"
                           (get-in doc [:actions :cancel :display
                                        :description]))))
            (is (= 200 (:status (act self :cancel))))
            (is (= "cancelled" (get (json (req :get self)) :state)))
            (is (= ["cancelled" "cancelled"]
                   [(get (json (req :get (get kyc :self))) :state)
                    (get (json (req :get (get wire-conf :self))) :state)])
                "the owns cascade cancelled the checklist")
            (let [doc (json (req :get self))]
              (is (= 0 (get-in doc [:data :open_items]))
                  "cancelled items are open nowhere"))))))))
