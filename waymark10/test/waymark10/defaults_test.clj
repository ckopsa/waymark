(ns waymark10.defaults-test
  "Field defaults (design §24): a `:default` entry property fills the
  ABSENT keys of a write at the doors (create + action input), before
  validation — an explicit null stays, a present value is never
  touched, vector-of-map items fill their own item defaults, and a
  mirror mint takes none (the authority's absence means absence).
  Defaults are law: the fingerprint's create facet and the action's
  input_defaults project them non-empty-only. The checks refuse a
  default the field would not accept, and any default on a derived
  field. Suite-local kinds; the door tests run on real Postgres."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.checks :as checks]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(defn- crate-map [& {:keys [qty-default note-default]
                     :or {qty-default 1}}]
  {:kind :df_crate
   :plural "df_crates"
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 40}]]
            [:unit {:optional true :default "g"} [:maybe [:enum "g" "ml"]]]
            [:note (cond-> {:optional true}
                     note-default (assoc :default note-default))
             [:maybe [:string {:max 40}]]]
            [:entries {:optional true}
             [:maybe [:vector [:map
                               [:day [:string {:max 10}]]
                               [:qty {:optional true :default qty-default}
                                [:maybe [:int {:min 1}]]]]]]]]
   :actions {:stamp {:from #{:open} :to :open
                     :input [:map
                             [:label [:string {:min 1 :max 40}]]
                             [:weight {:optional true :default 100}
                              [:maybe [:int {:min 1}]]]]
                     :safety {:idempotent true :reversible false
                              :confirm false}
                     :handler (fn [row inp _ctx]
                                (update row :data merge
                                        (select-keys inp [:label :weight])))}
             :close {:from #{:open} :to :closed
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Closed is history."}}}})

(def ^:private crate (r/resource (crate-map)))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

;; schema for the extra data fields stamp writes
(def ^:private crate*
  (r/resource
   (update (crate-map) :schema conj
           [:label {:optional true} [:maybe [:string {:max 40}]]]
           [:weight {:optional true} [:maybe [:int {:min 1}]]])))

;; ── 1. apply-defaults, the pure law ─────────────────────────────────

(deftest apply-defaults-semantics
  (let [form (:schema crate)]
    (testing "absent fills; present and explicit null stay"
      (is (= "g" (:unit (schema/apply-defaults form {:title "t"}))))
      (is (= "ml" (:unit (schema/apply-defaults form {:title "t" :unit "ml"}))))
      (is (nil? (:unit (schema/apply-defaults form {:title "t" :unit nil}))))
      (is (contains? (schema/apply-defaults form {:title "t" :unit nil}) :unit)
          "the explicit null is an answer, not an absence"))
    (testing "vector-of-map items fill their own defaults"
      (is (= [{:day "mon" :qty 1} {:day "tue" :qty 4}]
             (:entries (schema/apply-defaults
                        form {:title "t"
                              :entries [{:day "mon"} {:day "tue" :qty 4}]})))))))

;; ── 2. the doors ────────────────────────────────────────────────────

(def ^:private tables
  ["df_crates" "definitions" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_observations"])

(deftest defaults-fill-at-the-doors
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st))))
  (let [st (pg/storage db/dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [crate*]})]
        (testing "create: absent fills, explicit null stays"
          (let [row (:row (inv/create! eng :df_crate
                                       {:title "a"
                                        :entries [{:day "mon"}]}
                                       {:principal elena}))]
            (is (= "g" (get-in row [:data :unit])))
            (is (= 1 (get-in row [:data :entries 0 :qty]))))
          (let [row (:row (inv/create! eng :df_crate {:title "b" :unit nil}
                                       {:principal elena}))]
            (is (nil? (get-in row [:data :unit])))))
        (testing "action input: the absent field reaches the handler filled"
          (let [c (:row (inv/create! eng :df_crate {:title "c"}
                                     {:principal elena}))
                res (inv/invoke! eng :df_crate (:id c) :stamp {:label "x"}
                                 {:principal elena})]
            (is (= 100 (get-in res [:row :data :weight])))))
        (testing "a mint takes no defaults — the authority's absence
                  means absence"
          (let [row (:row (inv/create! eng :df_crate {:title "m"}
                                       {:principal elena :mint? true}))]
            (is (nil? (get-in row [:data :unit]))))))
      (finally (pg/close! st)))))

;; ── 3. the projection and the law ───────────────────────────────────

(deftest defaults-project-and-fingerprint
  (testing "the JSON-Schema default keyword rides the projection"
    (is (= "g" (get-in (schema/json-schema (:schema crate))
                       [:properties :unit :default]))))
  (testing "the create facet, flattened — item defaults dotted"
    (is (= {"unit" "g" "entries.qty" 1}
           (get-in (fp/fingerprint-of crate) ["create" "defaults"]))))
  (testing "input defaults ride the action facet"
    (is (= {"weight" 100}
           (get-in (fp/fingerprint-of crate)
                   ["machine" "actions" "stamp" "input_defaults"]))))
  (testing "the default-free world carries no facet"
    (let [bare (r/resource
                {:kind :df_bare :plural "df_bares"
                 :states [:open :closed] :initial :open :terminal #{:closed}
                 :summary "{data.title} · {state}"
                 :schema [:map [:title [:string {:max 40}]]]
                 :actions {:close {:from #{:open} :to :closed
                                   :safety {:idempotent true :reversible false
                                            :confirm false
                                            :one-way "Closed is history."}}}})]
      (is (nil? (get (fp/fingerprint-of bare) "create")))))
  (testing "changing a default is a law revision"
    (is (not= (fp/fingerprint-hash (fp/fingerprint-of crate))
              (fp/fingerprint-hash (fp/fingerprint-of
                                    (r/resource (crate-map :qty-default 2))))))))

;; ── 4. the checks refuse dishonest defaults ─────────────────────────

(deftest checks-refuse-dishonest-defaults
  (testing "a default the field refuses is a lie at the def site"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not a value this field accepts"
         (checks/run-all (r/resource (crate-map :note-default 42))))))
  (testing "a derived field takes no default"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"one fact, one writer"
         (checks/run-all
          (r/resource
           (-> (crate-map)
               (update :schema conj
                       [:heavy {:optional true :default true
                                :derived {:over [:title]
                                          :expr '(= (var :title) "x")}}
                        [:maybe :boolean]]))))))))
