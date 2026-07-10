(ns waymark10.batch-g-declare-test
  "Batch G, the declaration ergonomics: schema-entry colocation
  projects into the canonical top-level keys (and only there — the
  schema form and its JSON projection come out identical to the split
  spelling); a concern has exactly one home; defaction/defderived
  validate at the def line and def plain maps the inline spelling
  cannot be told apart from."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.declare :as d :refer [defaction defderived]]
            [waymark10.expr :as expr]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]))

;; ── a small kind, spelled twice ─────────────────────────────────────

(def split
  {:kind :gizmo
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.label} · {state}"
   :schema [:map
            [:label [:string {:max 40}]]
            [:qty [:int {:min 0}]]
            [:total {:optional true} [:maybe :int]]
            [:slots [:vector [:map [:pos :int]]]]]
   :derived {:total {:over [:qty]
                     :expr '(+ (var :qty) 1)}}
   :filterable {:qty #{:eq :range}}
   :sortable {:fields [:label :qty] :default "-qty"}
   :part-scopes {:slots {:path :slots :key :pos}}
   :actions {:close {:from #{:open} :to :closed
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Closing is cheap."}}}})

(def colocated
  (-> split
      (dissoc :derived :filterable :sortable :part-scopes)
      (assoc :schema
             [:map
              [:label {:sort true} [:string {:max 40}]]
              [:qty {:filter #{:eq :range} :sort :default-desc} [:int {:min 0}]]
              [:total {:optional true :derived {:over [:qty]
                                                :expr '(+ (var :qty) 1)}}
               [:maybe :int]]
              [:slots {:part-scope {:key :pos}} [:vector [:map [:pos :int]]]]])))

(deftest colocation-projects-into-the-canonical-map
  (let [n (r/normalize-resource colocated)]
    (is (= {:total {:over [:qty] :expr '(+ (var :qty) 1)}} (:derived n)))
    (is (= {:qty #{:eq :range}} (:filterable n)))
    (is (= {:fields [:label :qty] :default "-qty"} (:sortable n)))
    (is (= {:slots {:path :slots :key :pos}} (:part-scopes n)))))

(deftest the-sugar-never-reaches-the-schema
  (let [n (r/normalize-resource colocated)]
    (is (= (:schema (r/normalize-resource split)) (:schema n))
        "the stripped schema form IS the split spelling's form")
    (doseq [[_ {:keys [properties]}] (schema/entry-map (:schema n))]
      (is (empty? (select-keys properties [:derived :filter :sort :part-scope]))))
    (is (= (schema/json-schema (:schema split))
           (schema/json-schema (:schema n)))
        "the published JSON Schema is unchanged")))

(deftest two-spellings-one-fingerprint
  (is (= (r/fingerprint (r/normalize-resource split))
         (r/fingerprint (r/normalize-resource colocated))))
  (is (= (fp/fingerprint-hash (r/fingerprint (r/normalize-resource split)))
         (fp/fingerprint-hash (r/fingerprint (r/normalize-resource colocated))))))

(deftest the-two-spellings-are-one-normalized-map
  (is (= (r/normalize-resource split) (r/normalize-resource colocated))))

;; ── exactly one home ────────────────────────────────────────────────

(defn- one-home? [m]
  (try
    (r/normalize-resource m)
    false
    (catch clojure.lang.ExceptionInfo e
      (= :one-home (:check (ex-data e))))))

(deftest a-concern-has-exactly-one-home
  (testing "derived"
    (is (one-home? (assoc colocated :derived
                          {:total {:over [:qty] :expr '(+ (var :qty) 1)}}))))
  (testing "filter"
    (is (one-home? (assoc colocated :filterable {:qty #{:eq}}))))
  (testing "sort"
    (is (one-home? (assoc colocated :sortable {:fields [:qty]}))))
  (testing "part-scope"
    (is (one-home? (assoc colocated :part-scopes
                          {:slots {:path :slots :key :pos}}))))
  (testing "a different field at the top level is not a collision"
    (is (map? (r/normalize-resource
               (assoc colocated :filterable {:label #{:eq}}))))))

(deftest sort-marks-validate
  (testing "at most one default across both homes"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"at most one sort default"
         (r/normalize-resource
          (assoc colocated :schema
                 [:map
                  [:label {:sort :default} [:string {:max 40}]]
                  [:qty {:sort :default-desc} [:int {:min 0}]]
                  [:total {:optional true} [:maybe :int]]
                  [:slots [:vector [:map [:pos :int]]]]])))))
  (testing "a top-level default also counts"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"at most one sort default"
         (r/normalize-resource
          (-> split
              (assoc :sortable {:fields [:label] :default "label"})
              (assoc :schema
                     [:map
                      [:label [:string {:max 40}]]
                      [:qty {:sort :default} [:int {:min 0}]]
                      [:total {:optional true} [:maybe :int]]
                      [:slots [:vector [:map [:pos :int]]]]]))))))
  (testing "an unknown mark is refused"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":sort is true, :default, or :default-desc"
         (r/normalize-resource
          (assoc-in colocated [:schema 2 1 :sort] :descending))))))

(deftest a-colocated-part-scope-path-is-its-entry
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"path IS\s+its entry"
       (r/normalize-resource
        (assoc-in colocated [:schema 4 1 :part-scope] {:path :days :key :pos})))))

;; ── defaction ───────────────────────────────────────────────────────

(defaction close-it
  {:from #{:open} :to :closed
   :safety {:idempotent true :reversible false :confirm false
            :one-way "Closing is cheap."}})

(defaction stamp-it
  {:from #{:open} :to :open
   :input [:map [:note [:string {:max 40}]]]
   :safety {:idempotent true :reversible true :confirm false}
   :handler (fn [row inp _ctx]
              (assoc-in row [:data :label] (:note inp)))})

(deftest defaction-defs-the-plain-inline-map
  (is (= {:from #{:open} :to :closed
          :safety {:idempotent true :reversible false :confirm false
                   :one-way "Closing is cheap."}}
         close-it)
      "no normalization residue — the def'd value IS the inline spelling"))

(deftest defaction-captures-the-inline-handler-form
  (is (fn? (:handler stamp-it)))
  (is (= '(fn [row inp _ctx] (assoc-in row [:data :label] (:note inp)))
         (:waymark10/form (meta (:handler stamp-it))))
      "the same identity defhandler mints"))

(deftest defaction-validates-at-the-def-site
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"defaction/bad.*safety is declared"
       (d/action :bad {:from #{:open} :to :closed})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"defaction/bad.*:from is required"
       (d/action :bad {:to :closed
                       :safety {:idempotent true :reversible true :confirm false}})))
  (testing "through the macro, at load (the compiler wraps the refusal)"
    (let [e (try (eval '(waymark10.declare/defaction doomed
                          {:from #{:open} :to :closed}))
                 nil
                 (catch Throwable t t))]
      (is (some? e) "the def never lands")
      (is (re-find #"defaction/doomed.*safety is declared"
                   (ex-message (or (ex-cause e) e)))))))

(deftest a-defd-action-is-the-inline-action
  (let [inline (assoc-in split [:actions :close]
                         {:from #{:open} :to :closed
                          :safety {:idempotent true :reversible false :confirm false
                                   :one-way "Closing is cheap."}})
        defd (assoc-in split [:actions :close] close-it)]
    (is (= (r/normalize-resource inline) (r/normalize-resource defd)))
    (is (= (fp/fingerprint-hash (r/fingerprint (r/normalize-resource inline)))
           (fp/fingerprint-hash (r/fingerprint (r/normalize-resource defd)))))))

;; ── defderived ──────────────────────────────────────────────────────

(defderived total-spec
  {:over [:qty]
   :expr '(+ (var :qty) 1)})

(deftest defderived-normalizes-at-the-def-site
  (is (= (expr/normalize '(+ (var :qty) 1)) (:expr total-spec))
      "the def'd spec holds the canonical tree")
  (testing "count :where values land as sets"
    (is (= {:count {:owns :slot :where {:state #{"open"}}}}
           (d/derived :open-slots {:count {:owns :slot
                                           :where {:state ["open"]}}})))))

(deftest defderived-validates-scope-at-the-def-site
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"defderived bad.*never named"
       (d/derived :bad {:over [:qty] :expr '(+ (var :price) 1)})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"exactly one of :expr"
       (d/derived :bad {:over [:qty]})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"exactly one of :expr"
       (d/derived :bad {:expr '(+ (var :a) 1) :count {:owns :slot}})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #":over does not apply"
       (d/derived :bad {:over [:qty] :count {:owns :slot}}))))

(deftest a-defd-derived-works-inline-and-colocated
  (let [inline (assoc split :derived {:total total-spec})
        coloc (assoc colocated :schema
                     [:map
                      [:label {:sort true} [:string {:max 40}]]
                      [:qty {:filter #{:eq :range} :sort :default-desc}
                       [:int {:min 0}]]
                      [:total {:optional true :derived total-spec} [:maybe :int]]
                      [:slots {:part-scope {:key :pos}}
                       [:vector [:map [:pos :int]]]]])]
    (is (= (r/normalize-resource split)
           (r/normalize-resource inline)
           (r/normalize-resource coloc)))))
