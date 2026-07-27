(ns waymark10.schema
  "Schemas are data: the malli registry, the JSON-Schema projection,
  and the boundary transformers. One schema per surface, three
  consumers — validation, the published wire schema, and the
  fingerprint — all reading the same value.

  Waymark types:
    :waymark/date    — a LocalDate; ISO string on the wire
    :waymark/instant — a java.time.Instant; RFC 3339 string on the
                       wire (phase 8: prep_task.due_at demanded a
                       real point-in-time the clock can compare)
    :waymark/ref     — a cross-resource reference id; properties carry
                       {:kind … :label … :pick …} for the engine
    :waymark/vocab   — a vocabulary token; properties carry
                       {:open … :facet …}
    :decimal         — an exact BigDecimal (batch H: the capital
                       activity's money/percent amounts demanded it —
                       the E.lit(\"0.02\") lesson: never floats). The
                       storage projection, the checks, and the
                       collection grammar already spoke the :decimal
                       head; this registers the type they anticipated.
                       Properties honor {:min … :max …} exactly."
  (:require [clojure.string :as str]
            [clojure.walk]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as json-schema]
            [malli.registry :as mr]
            [malli.transform :as mt]
            [malli.util :as mu])
  (:import (java.time Instant LocalDate OffsetDateTime)))

(set! *warn-on-reflection* true)

;; ── the registry ────────────────────────────────────────────────────

(def ^:private waymark-date
  (m/-simple-schema
   {:type :waymark/date
    :pred #(instance? LocalDate %)
    :type-properties
    {:error/message "must be an ISO date (YYYY-MM-DD)"
     :decode/wire (fn [x] (if (string? x)
                            (try (LocalDate/parse ^String x)
                                 (catch Exception _ x))
                            x))
     :encode/wire (fn [x] (if (instance? LocalDate x) (str x) x))
     ;; generation (data-only, no test.check dep here): recent past
     ;; dates, so clock-gated guards hold by default under the walker
     :gen/schema [:int {:min 10957 :max 20088}]   ; 2000-01-01 … 2024-12-31
     :gen/fmap (fn [d] (LocalDate/ofEpochDay (long d)))
     :json-schema {:type "string" :format "date"}}}))

(def ^:private waymark-instant
  (m/-simple-schema
   {:type :waymark/instant
    :pred #(instance? Instant %)
    :type-properties
    {:error/message "must be an RFC 3339 date-time"
     :decode/wire (fn [x]
                    (if (string? x)
                      (try (Instant/parse ^String x)
                           (catch Exception _
                             (try (.toInstant (OffsetDateTime/parse ^String x))
                                  (catch Exception _ x))))
                      x))
     :encode/wire (fn [x] (if (instance? Instant x) (str x) x))
     ;; generation mirrors :waymark/date — recent past, so clock-gated
     ;; facts ("overdue") land in their default truth under the walker
     :gen/schema [:int {:min 946684800 :max 1735689600}] ; 2000…2024, epoch s
     :gen/fmap (fn [s] (Instant/ofEpochSecond (long s)))
     :json-schema {:type "string" :format "date-time"}}}))

(def ^:private waymark-ref
  (m/-simple-schema
   {:type :waymark/ref
    :pred string?
    :type-properties
    {:error/message "must be a resource id"
     :gen/schema [:int {:min 0 :max 999}]
     :gen/fmap (fn [n] (str "ref-" n))
     :json-schema {:type "string" :format "waymark-ref"}}}))

(def ^:private waymark-vocab
  (m/-simple-schema
   {:type :waymark/vocab
    :pred string?
    :type-properties
    {:error/message "must be a vocabulary token"
     :json-schema {:type "string" :format "waymark-vocab"}}}))

(def ^:private waymark-decimal
  ;; exact decimals only — a JSON float decodes to BigDecimal (the
  ;; wire mapper already parses {:bigdecimals true}), an integer or
  ;; string coerces exactly, and validation never accepts a double
  (m/-simple-schema
   {:type :decimal
    :compile
    (fn [{:keys [min max gt lt]} _children _opts]
      {:pred (fn [x]
               (and (decimal? x)
                    (or (nil? min)
                        (>= (.compareTo ^java.math.BigDecimal x (bigdec min)) 0))
                    (or (nil? max)
                        (<= (.compareTo ^java.math.BigDecimal x (bigdec max)) 0))
                    ;; exclusive bounds (design §24): a ratio is > 0,
                    ;; not ≥ some arbitrary floor
                    (or (nil? gt)
                        (pos? (.compareTo ^java.math.BigDecimal x (bigdec gt))))
                    (or (nil? lt)
                        (neg? (.compareTo ^java.math.BigDecimal x (bigdec lt))))))
       :type-properties
       {:error/message "must be an exact decimal number"
        :decode/wire (fn [x]
                       (cond
                         (decimal? x) x
                         (number? x) (bigdec x)
                         (string? x) (try (bigdec x)
                                          (catch Exception _ x))
                         :else x))
        :encode/wire identity
        ;; generation mirrors the small-positive posture of the other
        ;; waymark types: amounts the walker writes validate everywhere
        ;; (an exclusive bound floors/ceils one whole step inside)
        :gen/schema [:int {:min (long (or min (some-> gt long inc) 0))
                           :max (long (or max (some-> lt long) 100))}]
        :gen/fmap (fn [n] (bigdec n))
        :json-schema (cond-> {:type "number" :format "decimal"}
                       min (assoc :minimum min)
                       max (assoc :maximum max)
                       gt (assoc :exclusiveMinimum gt)
                       lt (assoc :exclusiveMaximum lt))}})}))

(def registry
  (mr/composite-registry
   (m/default-schemas)
   (mu/schemas)
   {:waymark/date waymark-date
    :waymark/instant waymark-instant
    :waymark/ref waymark-ref
    :waymark/vocab waymark-vocab
    :decimal waymark-decimal}))

(def options {:registry registry})

(defn schema
  "Compile a schema form against the waymark registry."
  [form]
  (m/schema form options))

;; ── validation ──────────────────────────────────────────────────────

(def wire-transformer
  "Decode wire JSON into schema types (ISO strings → LocalDate, …);
  encode back for the wire. Unknown keys pass through untouched —
  refusal, not silent stripping, is the input contract's job
  (closed-errors)."
  (mt/transformer {:name :wire}))

(defn decode
  "Decode a wire value (parsed JSON, keyword keys) against a schema
  form. Decode before validating: validation speaks schema types."
  [form value]
  (m/decode (schema form) value wire-transformer))

(defn encode [form value]
  (m/encode (schema form) value wire-transformer))

(defn validate [form value]
  (m/validate (schema form) value))

(defn errors
  "Humanized, field-keyed errors for the 422 surface; nil when valid.
  Validates DECODED values."
  [form value]
  (some-> (m/explain (schema form) value)
          (me/humanize)))

(defn closed-errors
  "The input contract: maps are closed — an undeclared key is a
  field-keyed refusal, never silently dropped. Validates DECODED
  values."
  [form value]
  (some-> (m/explain (mu/closed-schema (schema form) options) value)
          (me/humanize)))

(defn partial-closed-errors
  "The draft contract (phase 7): closed like an input, but nothing is
  required — a draft is allowed to be incomplete, never to smuggle
  unknown fields or broken values. Validates DECODED values."
  [form value]
  (some-> (m/explain (mu/optional-keys (mu/closed-schema (schema form) options)
                                       nil options)
                     value)
          (me/humanize)))

;; ── defaults (design §24) ───────────────────────────────────────────

(declare entry-map)

(defn apply-defaults
  "Fill ABSENT keys of a decoded map with the entries' declared
  :default values — an explicit null stays (the author said blank),
  and a present value is never touched. Recurses into vector-of-map
  items so an embedded item fills its own item defaults (a birthing
  sighting's quantity). Applied at the write doors (create + action
  input) BEFORE validation; stored documents leave the doors complete,
  so reads never default."
  [form value]
  (if-not (map? value)
    value
    (reduce-kv
     (fn [v k {:keys [properties schema]}]
       (let [s (if (and (vector? schema) (= :maybe (first schema)))
                 (second schema)
                 schema)
             item-form (when (and (vector? s) (= :vector (first s)))
                         (last s))
             v (if (and (contains? properties :default)
                        (not (contains? v k)))
                 (assoc v k (:default properties))
                 v)]
         (if (and (vector? item-form)
                  (= :map (first item-form))
                  (vector? (get v k)))
           (assoc v k (mapv #(apply-defaults item-form %) (get v k)))
           v)))
     value
     (entry-map form))))

;; ── introspection (what the checks read) ────────────────────────────

(defn map-schema?
  [form]
  (= :map (m/type (schema form))))

(defn entry-keys
  "The declared keys of a :map schema, in declaration order."
  [form]
  (mapv key (m/entries (schema form) options)))

(defn entry-map
  "key → {:optional bool :properties … :schema <child form>} for a
  :map schema — the introspection surface the checks and the
  fingerprint read instead of Pydantic model fields."
  [form]
  (into {}
        (map (fn [[k props child]]
               [k {:optional (boolean (:optional props))
                   :properties (dissoc props :optional)
                   :schema (m/form child options)}]))
        (m/children (schema form) options)))

(defn field-schema
  "The child schema form for one key of a :map schema, nil when the
  key is not declared. Unwraps :maybe."
  [form k]
  (when-some [entry (get (entry-map form) k)]
    (let [s (:schema entry)]
      (if (and (vector? s) (= :maybe (first s)))
        (second s)
        s))))

;; ── filter values (one home, two readers) ───────────────────────────
;; A filter value crosses the wire as text and casts server-side, so
;; "does this string decode into that field's type" is a question the
;; REQUEST asks (waymark10.server.collections' query grammar) and the
;; DECLARATION gate asks (waymark10.checks' :default-filters battery,
;; which must refuse a default the door would reject). Both readings
;; live here so the two answers can never drift apart.

(defn leaf-head
  "The leaf type of a schema form, :maybe/:vector layers and property
  maps unwrapped: [:vector [:waymark/vocab {:open true}]] →
  :waymark/vocab."
  [s]
  (cond
    (keyword? s) s
    (vector? s) (if (#{:maybe :vector} (first s))
                  (leaf-head (last s))
                  (first s))
    :else nil))

(defn- instant-problem [^String raw]
  (when-not (or (try (OffsetDateTime/parse raw) true (catch Exception _ false))
                (try (Instant/parse raw) true (catch Exception _ false)))
    "must be an RFC 3339 date-time"))

(defn filter-value-problem
  "Decode-check one filter value against a field's schema head → nil
  when fine, else the per-field error sentence. Values cross to
  storage as strings and cast server-side, so this refuses only what
  the cast would choke on — a text field takes any text.
  head :waymark/instant is also the answer for the strict temporal
  bounds (_after/_before) over a non-date field."
  [head ^String raw]
  (case head
    :waymark/date (when-not (try (LocalDate/parse raw) true
                                 (catch Exception _ false))
                    "must be an ISO date (YYYY-MM-DD)")
    :waymark/instant (instant-problem raw)
    :int (when (nil? (parse-long raw)) "must be an integer")
    (:double :decimal) (when (nil? (parse-double raw)) "must be a number")
    :boolean (when-not (contains? #{"true" "false"} raw)
               "must be true or false")
    nil))

;; ── the JSON-Schema projection ──────────────────────────────────────

(defn ref-props
  "The x-ref advertisement of one schema entry's properties — the
  target kind and the picker's own hints — or nil when the entry
  names no target. Public because two surfaces publish it from one
  spelling: the JSON-Schema projection below (the field's own
  entry) and the collection query schema (the filter param that
  filters BY that field), so a ref filters through the same labeled
  picker the form offers."
  [props]
  (when (:kind props)
    (into {} (filter (comp some? val))
          (-> (select-keys props [:kind :label :carry :pick :predecessor])
              ;; :pick crosses the wire as the picker's literal
              ;; query params — keyword values land as their names
              (update :pick
                      (fn [p]
                        (when p
                          (into {}
                                (map (fn [[f v]]
                                       [f (if (coll? v)
                                            (mapv #(if (keyword? %) (name %) %) v)
                                            (if (keyword? v) (name v) v))]))
                                p))))))))

(defn- entry-x-props
  "Waymark declaration properties ride the JSON Schema as x-* keys —
  presentation and engine hints the generic client reads and agents
  ignore. Encoded as :json-schema/* properties, malli's sanctioned
  merge point."
  [props]
  (cond-> {}
    (:x-display props)
    (assoc :json-schema/x-display (:x-display props))
    (:kind props)
    (assoc :json-schema/x-ref (ref-props props))
    (contains? props :open)
    (assoc :json-schema/x-vocab
           (into {} (filter (comp some? val))
                 (select-keys props [:open :facet :placeholder])))
    ;; the declared default rides as the standard JSON-Schema keyword —
    ;; the form prefills it, the engine applies it to absent keys
    (contains? props :default)
    (assoc :json-schema/default (:default props))))

(defn- annotate
  "Walk a schema form, promoting waymark entry properties to
  :json-schema/x-* so they surface in the projection."
  [form]
  (if-not (vector? form)
    form
    (let [[t & more] form
          [props children] (if (map? (first more))
                             [(first more) (rest more)]
                             [nil more])]
      (if (= :map t)
        (into (cond-> [t] props (conj props))
              (map (fn [entry]
                     (if (vector? entry)
                       (let [[k & emore] entry
                             [eprops [child]] (if (map? (first emore))
                                                [(first emore) (rest emore)]
                                                [{} emore])
                             eprops (merge eprops (entry-x-props eprops))]
                         (if (seq eprops)
                           [k eprops (annotate child)]
                           [k (annotate child)]))
                       entry)))
              children)
        (into (cond-> [t] props (conj props))
              (map annotate)
              children)))))

(defn- inline-definitions
  "Registry-named leaf types come back as $ref + definitions; the
  published schema inlines them (spec: small schemas inline)."
  [js]
  (let [defs (:definitions js)]
    (if (empty? defs)
      js
      (clojure.walk/postwalk
       (fn [x]
         (if-some [r (and (map? x) (:$ref x))]
           (let [n (str/replace-first (str r) "#/definitions/" "")]
             (if-some [d (or (get defs n) (get defs (keyword n)))]
               (merge d (dissoc x :$ref))
               x))
           x))
       (dissoc js :definitions)))))

(defn json-schema
  "The published JSON Schema of a schema form. Waymark types carry
  their formats inline; declaration properties surface as x-* keys."
  [form]
  (-> (annotate form)
      (m/schema options)
      (json-schema/transform options)
      inline-definitions))
