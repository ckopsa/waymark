(ns waymark10.secret-disposition-test
  "The secret field disposition (waymark-kyg): a schema entry
  declaring {:secret true} whose VALUE never leaves the engine in any
  projection, scoped or unscoped — envelope data/fields/summary,
  envelope-stub, collection items, the worksheet export, and the
  published schema views all conceal it with NO visibility in play,
  while guards, handlers and the system door still read the full row
  and the write doors still accept it. The declaration gate refuses
  every surface that would materialize or print the value:
  filterable/sortable/faceted, summary and label templates, worksheet
  columns, vocabulary fields, nested marks, and :state. Storage is
  the in-memory twin — parity with the Postgres meanings is the
  render layer's own claim."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [waymark10.fingerprint :as fingerprint]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.openapi :as openapi]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.worksheet :as worksheet]
            [waymark10.server.xlsx :as xlsx]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private ana (t/principal {:id "ana" :type :human :display "Ana"}))
(def ^:private sys (t/principal {:id "rotator" :type :system
                                 :display "Key rotator"}))

(def ^:private keyed
  (g/expr {:name :keyed
           :when '(is-set (data :api_key))
           :explain "No key is set."}))

(r/defhandler rename-handler [row inp _ctx]
  (assoc-in row [:data :name] (:name inp)))

(r/defhandler rotate-handler [row inp _ctx]
  (assoc-in row [:data :api_key] (:api_key inp)))

(r/defhandler edit-token-handler [row inp _ctx]
  (assoc-in row [:data :token] (:token inp)))

;; a code guard whose :vars-fn garnish names a :secret field: its
;; refusal narration would print the value unless render conceals the
;; row it renders over (waymark-kyg, finding #4)
(def ^:private leaky-narrator
  (g/guard {:name :leaky-narrator
            :explain "The key is {api_key}."
            :judges []
            :check (fn [_row _inp _ctx] (t/deny))
            :vars ["api_key"]
            :vars-fn (fn [row] {:api_key (get-in row [:data :api_key])})}))

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "The prior value is on the audit trail."})

(def vault
  (r/resource
   {:kind :vault
    :states [:open :sealed]
    :initial :open
    :terminal #{:sealed}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 80}]]
             [:api_key {:optional true :secret true}
              [:maybe [:string {:max 200}]]]
             [:notes {:optional true} [:maybe [:string {:max 200}]]]]
    :filterable {:state #{:eq :in} :name #{:eq}}
    :worksheet {:columns [{:field :name :action :rename}
                          {:field :notes}]
                :create true}
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map [:name [:string {:min 1 :max 80}]]]
              :waives #{:edit-shape}
              :safety quiet
              :handler rename-handler}
     :rotate {:from #{:open} :to :open
              :input [:map [:api_key [:string {:min 1 :max 200}]]]
              :waives #{:edit-shape}
              :safety quiet
              :handler rotate-handler}
     :seal {:from #{:open} :to :sealed
            :guards [keyed]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A sealed vault stays sealed."}}}}))

(def ^:dynamic *eng* nil)

(use-fixtures :each
  (fn [f]
    (binding [*eng* (inv/engine {:storage (memory/storage)
                                 :resources [vault]})]
      (f))))

(def ^:private secret-value "sk-TOPSECRET-000")

(defn- create-vault! []
  (:row (inv/create! *eng* :vault {:name "Prod" :api_key secret-value}
                     {:principal ana})))

(defn- env-of [row]
  (render/envelope vault row {:now (Instant/now)}))

;; ── the envelope, unscoped ──────────────────────────────────────────

(deftest envelope-conceals-the-value-everywhere
  (let [row (create-vault!)
        env (env-of row)]
    (testing "the stored row holds the value; the projection never does"
      (is (= secret-value (get-in row [:data :api_key])))
      (is (not (contains? (get env "data") "api_key")))
      (is (not (contains? (get env "fields") "api_key")))
      (is (not (str/includes? (wire/write-json env) secret-value))))
    (testing "the summary renders from the honest fields"
      (is (= "Prod · Open" (get env "summary"))))
    (testing "an action INPUT may still name the parameter — inputs
              are not row schema"
      (is (contains? (get-in env ["actions" "rotate" "input" "properties"])
                     "api_key")))))

(deftest envelope-stub-and-summary-conceal-too
  (let [row (create-vault!)
        stub (render/envelope-summary vault row {:rows :none
                                                 :now (Instant/now)})
        summary (render/envelope-summary vault row {:now (Instant/now)})]
    (is (nil? (get stub "actions")))
    (is (not (str/includes? (wire/write-json stub) secret-value)))
    (is (not (contains? (get summary "fields") "api_key")))
    (is (not (str/includes? (wire/write-json summary) secret-value)))))

(deftest guards-judge-the-full-row
  (testing "the guard reads what the projection conceals"
    (let [bare (:row (inv/create! *eng* :vault {:name "Bare"}
                                  {:principal ana}))
          keyed-row (create-vault!)]
      (is (contains? (get (env-of bare) "unavailable") "seal"))
      (is (contains? (get (env-of keyed-row) "actions") "seal")))))

(deftest the-store-keeps-it-and-the-system-door-writes-it
  (let [row (create-vault!)
        _ (inv/invoke! *eng* :vault (:id row) :rotate
                       {:api_key "sk-ROTATED-111"} {:principal sys})
        raw (store/with-tx (:storage *eng*)
              (fn [tx]
                (store/load-row (:storage *eng*) tx :vault (:id row) {})))
        stored (inv/decode-row vault raw)]
    (is (= "sk-ROTATED-111" (get-in stored [:data :api_key])))
    (is (not (str/includes? (wire/write-json (env-of stored))
                            "sk-ROTATED-111")))))

;; ── the collection ──────────────────────────────────────────────────

(deftest collection-items-and-create-advert-conceal
  (create-vault!)
  (let [col (collections/envelope *eng* vault {} {:now (Instant/now)})]
    (is (pos? (count (get-in col ["data" "items"]))))
    (is (not (str/includes? (wire/write-json col) secret-value)))
    (testing "the advertised create input never names the field the
              door still accepts"
      (is (not (contains? (get-in col ["actions" "create" "input"
                                       "properties"])
                          "api_key"))))))

;; ── the worksheet export ────────────────────────────────────────────

(deftest a-legal-worksheet-never-carries-the-field
  (create-vault!)
  (let [res (worksheet/export *eng* vault {})
        sheet (xlsx/read-sheet (:body res))]
    (is (= 200 (:status res)))
    (is (= ["id" "version" "state" "name" "notes"] (first sheet)))
    (is (not (str/includes? (wire/write-json sheet) secret-value)))))

;; ── the published schema views ──────────────────────────────────────

(deftest published-schemas-drop-the-entry
  (let [js (schema/conceal (schema/json-schema (:schema vault))
                           (schema/secret-fields (:schema vault)))]
    (testing "without a visibility (the /api/schemas composition)"
      (is (not (contains? (:properties js) :api_key)))
      (is (not-any? #(= "api_key" (name %)) (:required js []))))
    (testing "with a visibility that admits every field"
      (let [vis {:field? (fn [_ _] true) :hashed? (fn [_ _] false)}
            projected (grants/project-json-schema vis :vault js)]
        (is (not (contains? (:properties projected) :api_key)))))
    (testing "the openapi create body"
      (let [doc (openapi/document *eng*)
            body (get-in doc [:paths "/api/vaults" :post :requestBody
                              :content "application/json" :schema])]
        (is (contains? (:properties body) :name))
        (is (not (contains? (:properties body) :api_key)))))))

;; ── the honesty trap for display titles ─────────────────────────────

(deftest a-display-title-reading-a-secret-field-falls-back
  (let [leaky (r/resource
               {:kind :leaky
                :states [:open]
                :initial :open
                :terminal #{}
                :allow-dead #{:open}
                :summary "{data.name} · {state}"
                :display {:title "{data.token}"}
                :schema [:map
                         [:name [:string {:max 40}]]
                         [:token {:optional true :secret true}
                          [:maybe [:string {:max 40}]]]]})
        row {:id "l1" :state :open :version 1
             :data {:name "Leak" :token secret-value}}
        env (render/envelope leaky row {:now (Instant/now)})]
    (is (not (str/includes? (str (get-in env ["display" "title"]))
                            secret-value)))
    (is (not (str/includes? (wire/write-json env) secret-value)))))

;; ── a secret part path drops its parts group whole ──────────────────

(deftest a-secret-part-path-conceals-the-group
  (let [book (r/resource
              {:kind :coupon_book
               :states [:open :done]
               :initial :open
               :terminal #{:done}
               :allow-dead #{:done}
               :summary "{data.name} · {state}"
               :schema [:map
                        [:name [:string {:max 40}]]
                        [:codes {:optional true :secret true
                                 :part-scope {:key :code}}
                         [:maybe [:vector [:map [:code [:string {:max 20}]]]]]]]
               :actions
               {:redeem {:from #{:open} :to :open
                         :place :codes
                         :input [:map [:code [:string {:max 20}]]]
                         :safety {:idempotent true :reversible false
                                  :confirm false
                                  :one-way "A redeemed code is spent."}}}})
        row {:id "b1" :state :open :version 1
             :data {:name "Book" :codes [{:code "a1"} {:code "a2"}]}}
        env (render/envelope book row {:now (Instant/now)})]
    (is (not (contains? (get env "data") "codes")))
    (is (nil? (get env "parts")))
    (is (not (str/includes? (wire/write-json env) "a1")))))

;; ── the declaration gate ────────────────────────────────────────────

(defn- base-kind [& {:as overrides}]
  (merge {:kind :gated
          :states [:open]
          :initial :open
          :terminal #{}
          :allow-dead #{:open}
          :summary "{data.name} · {state}"
          :schema [:map
                   [:name [:string {:max 40}]]
                   [:token {:optional true :secret true}
                    [:maybe [:string {:max 40}]]]]}
         overrides))

(defn- refused? [rmap]
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\[secret\]"
                        (r/resource rmap))))

(deftest the-gate-refuses-every-materializing-surface
  (testing "filterable"
    (refused? (base-kind :filterable {:token #{:eq}})))
  (testing "sortable fields"
    (refused? (base-kind :sortable {:fields [:token]})))
  (testing "the sort default"
    (refused? (base-kind :sortable {:fields [:name] :default "-token"})))
  (testing "faceted"
    (refused? (base-kind :faceted [:token])))
  (testing "the summary template"
    (refused? (base-kind :summary "{data.token} · {state}")))
  (testing "the label template"
    (refused? (base-kind :label-template "{data.token}")))
  (testing "a worksheet column"
    (refused? (base-kind
               :worksheet {:columns [{:field :token}]}
               :actions {:touch {:from #{:open} :to :open
                                 :safety {:idempotent true :reversible false
                                          :confirm false
                                          :one-way "Nothing changes."}}})))
  (testing "a vocabulary field — it filters and facets by declaration"
    (refused? (base-kind :schema [:map
                                  [:name [:string {:max 40}]]
                                  [:tag {:optional true :secret true}
                                   [:maybe [:waymark/vocab {:open true}]]]])))
  (testing ":state is machine, not data"
    (refused? (base-kind :schema [:map
                                  [:name [:string {:max 40}]]
                                  [:state {:optional true :secret true}
                                   [:maybe [:string {:max 20}]]]])))
  (testing "a nested mark declares nothing — refused, never ignored"
    (refused? (base-kind :schema [:map
                                  [:name [:string {:max 40}]]
                                  [:meta {:optional true}
                                   [:maybe [:map
                                            [:token {:secret true}
                                             [:string {:max 40}]]]]]])))
  (testing "the {:x-display {:secret true}} misspelling declares
            nothing — refused, never silently rendered in the clear"
    (refused? (base-kind :schema [:map
                                  [:name [:string {:max 40}]]
                                  [:token {:optional true
                                           :x-display {:secret true}}
                                   [:maybe [:string {:max 40}]]]])))
  (testing "an own-kind derivation reading a :secret field re-emits it"
    (refused? (base-kind
               :schema [:map
                        [:name [:string {:max 40}]]
                        [:token {:optional true :secret true}
                         [:maybe [:string {:max 40}]]]
                        [:masked {:optional true} [:maybe :boolean]]]
               :derived {:masked {:over [:token]
                                  :expr '(is-set (var :token))}})))
  (testing "an :edit :prefill of a :secret field serves it raw"
    (refused? (base-kind
               :actions {:edit_token
                         {:from #{:open} :to :open
                          :input [:map [:token [:string {:max 40}]]]
                          :edit {:prefill [:token]}
                          :waives #{:edit-shape}
                          :safety {:idempotent true :reversible false
                                   :confirm false :one-way "Prior on the log."}
                          :handler edit-token-handler}}))))

(deftest a-legal-secret-declaration-boots
  (is (some? (r/resource (base-kind))))
  (is (= #{:api_key} (schema/secret-fields (:schema vault)))))

;; ── the fingerprint carries the disposition (finding #2) ────────────

(deftest secret-rides-the-fingerprint
  (let [decl {:kind :fp_probe :plural "fp_probes"
              :states [:open] :initial :open
              :terminal #{} :allow-dead #{:open}
              :summary "{data.name} · {state}"
              :schema [:map
                       [:name [:string {:max 40}]]
                       [:token {:optional true} [:maybe [:string {:max 40}]]]]}
        plain (r/resource decl)
        secret (r/resource
                (assoc decl :schema
                       [:map
                        [:name [:string {:max 40}]]
                        [:token {:optional true :secret true}
                         [:maybe [:string {:max 40}]]]]))
        fp-plain (fingerprint/fingerprint-of plain)
        fp-secret (fingerprint/fingerprint-of secret)]
    (testing "the projection includes the secret set (shape-class)"
      (is (nil? (get fp-plain "shape")))
      (is (= ["token"] (get-in fp-secret ["shape" "secret"]))))
    (testing "toggling :secret moves the hash — a concealed→readable
              flip mints a law revision and shows in the diff"
      (is (not= (fingerprint/fingerprint-hash fp-plain)
                (fingerprint/fingerprint-hash fp-secret)))
      (let [diff (fingerprint/diff-fingerprints fp-plain fp-secret)]
        (is (some #(= :shape (:class %)) (:added diff)))))))

;; ── the refusal narration conceals the value (finding #4) ───────────

(deftest an-unavailable-narration-never-prints-the-secret
  (let [narrated
        (r/resource
         {:kind :narrated
          :states [:open]
          :initial :open
          :terminal #{}
          :allow-dead #{:open}
          :summary "{data.name} · {state}"
          :schema [:map
                   [:name [:string {:max 40}]]
                   [:api_key {:optional true :secret true}
                    [:maybe [:string {:max 200}]]]]
          :actions
          {:spend {:from #{:open} :to :open
                   :guards [leaky-narrator]
                   :safety {:idempotent true :reversible false
                            :confirm false :one-way "x"}}}})
        row {:id "n1" :state :open :version 1
             :data {:name "N" :api_key secret-value}}
        env (render/envelope narrated row {:now (Instant/now)})
        reason (get-in env ["unavailable" "spend" "reason"])]
    (testing "the guard denied, so the action narrates its unavailability"
      (is (some? reason)))
    (testing "the reason renders over the concealed row — no secret value"
      (is (not (str/includes? (str reason) secret-value)))
      (is (not (str/includes? (wire/write-json env) secret-value))))))
