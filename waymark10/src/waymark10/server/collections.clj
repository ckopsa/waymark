(ns waymark10.server.collections
  "The collection surface (phase 7): GET /api/{plural} filtered by the
  declared :filterable grammar — field= (:eq), field=a,b (:in comma
  list), field_gte=/field_lte= (:range), field_after= (:after), state=
  always, vocab-array membership via JSONB containment — ordered by
  the declared :sortable fields over their promoted columns, paged
  1-based (size cap 100, default 25). The envelope carries the items
  as envelope-minus-data summaries, the REAL filtered total, the
  create/query/bulk affordances (the query input schema is generated
  from the filterable/sortable declarations, with x-facets counts on
  :faceted fields), and next/prev links (omitted at the edges).

  Recorded choices and punts:
  - unknown or malformed query parameters are one 422 naming every
    bad parameter (waymark9 parse_query).
  - filter enforcement runs the phase-6 cond grammar (data->> + cast),
    not the generated columns — same semantics, one grammar; only
    sort leans on the promoted columns. GIN indexes for vocab arrays
    are unbuilt (named punt): containment filters scan.
  - facet counts are best-effort and read in their own transaction
    (a failed facet query must not poison the page's transaction):
    an error drops the counts with a *err* warning, never the page.
  - x-facets counts splice into the wired query schema AFTER the
    kebab→snake boundary, so a hyphenated vocab token survives as a
    count key.
  - the create entry advertises the raw create schema — create-guard
    acceptance folding (waymark9 §10) is unported here.
  - waymark9's rows=none, depth= and the recomputing un-advertising
    have no v10 counterpart yet — deferred with their machinery."
  (:require [clojure.string :as str]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.time Instant LocalDate OffsetDateTime)))

(set! *warn-on-reflection* true)

(def page-size-default 25)
(def page-size-max 100)

;; ── field typing ────────────────────────────────────────────────────

(defn- head-of
  "The leaf type of a schema form, unwrapping :maybe/:vector and
  property maps."
  [s]
  (cond
    (keyword? s) s
    (vector? s) (if (#{:maybe :vector} (first s))
                  (head-of (last s))
                  (first s))
    :else nil))

(defn- field-info [rdef f]
  (let [s (schema/field-schema (:schema rdef) f)]
    {:head (head-of s)
     :array? (boolean (and (vector? s) (= :vector (first s))))}))

(defn- cast-of [head]
  (case head
    :waymark/date "date"
    :waymark/instant "timestamptz"
    :int "bigint"
    :boolean "boolean"
    (:double :decimal) "numeric"
    "text"))

(defn- check-instant [^String raw]
  (when-not (or (try (OffsetDateTime/parse raw) true (catch Exception _ false))
                (try (Instant/parse raw) true (catch Exception _ false)))
    "must be an RFC 3339 date-time"))

(defn- check-value
  "Decode-check one filter value against the field's schema head —
  values cross to storage as strings and cast server-side, so this
  only refuses what the cast would choke on. → nil when fine, else
  the per-field error sentence."
  [head ^String raw]
  (case head
    :waymark/date (when-not (try (LocalDate/parse raw) true
                                 (catch Exception _ false))
                    "must be an ISO date (YYYY-MM-DD)")
    :waymark/instant (check-instant raw)
    :int (when (nil? (parse-long raw)) "must be an integer")
    (:double :decimal) (when (nil? (parse-double raw)) "must be a number")
    :boolean (when-not (contains? #{"true" "false"} raw)
               "must be true or false")
    nil))

;; ── the filter grammar ──────────────────────────────────────────────

(defn- grammar
  "param name → {:field :mode :in? :array? :head (:states)} for one
  declared kind. state= always filters, enum-checked against the
  machine; everything else comes from :filterable."
  [rdef]
  (let [entries
        (into {}
              (mapcat
               (fn [[f ops]]
                 (when-not (= :state f)
                   (let [{:keys [head array?]} (field-info rdef f)
                         fname (name f)
                         e {:field f :head head :array? array?}]
                     (concat
                      (when (or (:eq ops) (:in ops) array?)
                        [[fname (assoc e :mode :eq
                                       :in? (boolean (or (:in ops) array?)))]])
                      (when (:range ops)
                        [[(str fname "_gte") (assoc e :mode :gte)]
                         [(str fname "_lte") (assoc e :mode :lte)]])
                      (when (:after ops)
                        [[(str fname "_after") (assoc e :mode :after)]]))))))
              (:filterable rdef))]
    (assoc entries "state" {:field :state :state? true :mode :eq :in? true
                            :states (into #{} (map name) (:states rdef))})))

(defn- cond-of
  "One validated param → one storage cond (the phase-6 grammar,
  widened with :in-any for vocab arrays)."
  [e values]
  (let [f (:field e)
        multi? (< 1 (count values))
        v (first values)]
    (cond
      (:state? e) (if multi?
                    {:target :state :op :in :values values}
                    {:target :state :op := :value v})
      (:array? e) {:target :data :field f :op :in-any :values values}
      :else
      (let [cast (cast-of (:head e))
            base {:target :data :field f :cast cast}]
        (case (:mode e)
          :eq (if multi?
                (assoc base :op :in :values values)
                (assoc base :op := :value v))
          :gte (assoc base :op :>= :value v)
          :lte (assoc base :op :<= :value v)
          :after (assoc base :op :> :value v
                        :cast (if (= "date" cast) "date" "timestamptz")))))))

(defn parse-query
  "Query params ({string string}) → {:conds […] :sort {:field :desc}
  :page {:size :number} :filters sorted-map :applied sorted-map}, or
  one 422 problem naming every unknown/malformed parameter."
  [rdef params]
  (let [g (grammar rdef)
        sortable (mapv name (get-in rdef [:sortable :fields]))
        default-sort (get-in rdef [:sortable :default])
        acc
        (reduce-kv
         (fn [acc pname raw]
           (cond
             (= "sort" pname)
             (let [desc? (str/starts-with? raw "-")
                   base (if desc? (subs raw 1) raw)]
               (if (some #(= base %) sortable)
                 (assoc acc :sort {:field (keyword base) :desc desc?})
                 (update acc :errors assoc pname
                         [(if (seq sortable)
                            (str "must be one of "
                                 (vec (mapcat (fn [f] [f (str "-" f)]) sortable)))
                            "this kind declares no sortable fields")])))

             (= "page[size]" pname)
             (let [n (parse-long raw)]
               (if (and n (<= 1 n page-size-max))
                 (assoc-in acc [:page :size] n)
                 (update acc :errors assoc pname
                         [(str "must be an integer 1.." page-size-max)])))

             (= "page[number]" pname)
             (let [n (parse-long raw)]
               (if (and n (<= 1 n))
                 (assoc-in acc [:page :number] n)
                 (update acc :errors assoc pname ["must be a positive integer"])))

             :else
             (if-some [e (get g pname)]
               (let [values (if (and (:in? e) (str/includes? raw ","))
                              (into [] (comp (map str/trim) (remove str/blank?))
                                    (str/split raw #","))
                              [raw])
                     errs (into []
                                (comp
                                 (keep (fn [v]
                                         (cond
                                           (:state? e)
                                           (when-not (contains? (:states e) v)
                                             (str (pr-str v) " is not a state; one of "
                                                  (vec (sort (:states e)))))
                                           (and (= :after (:mode e))
                                                (not= :waymark/date (:head e)))
                                           (check-instant v)
                                           :else (check-value (:head e) v))))
                                 (distinct))
                                values)]
                 (cond
                   (empty? values)
                   (update acc :errors assoc pname ["must carry at least one value"])

                   (seq errs) (update acc :errors assoc pname errs)

                   :else (-> acc
                             (update :conds conj (cond-of e values))
                             (update :filters assoc pname raw))))
               (update acc :errors assoc pname ["unknown query parameter"]))))
         {:conds [] :errors {} :filters (sorted-map) :sort nil
          :page {:size page-size-default :number 1}}
         params)]
    (when (seq (:errors acc))
      (throw (p/schema-invalid :query (:errors acc))))
    (-> acc
        (dissoc :errors)
        (assoc :applied (into (sorted-map) params))
        (update :sort #(or % (when default-sort
                               {:field (keyword (str/replace-first default-sort "-" ""))
                                :desc (str/starts-with? default-sort "-")}))))))

;; ── the query affordance's input schema ─────────────────────────────

(defn- bound-of
  "The typed bound for a range parameter — temporal fields advertise
  honest ISO strings, numeric stay numeric (waymark9 design 6.0 §3)."
  [base]
  (cond
    (#{"date" "date-time"} (:format base)) {:type "string"
                                            :format (:format base)}
    (#{"integer" "number"} (:type base)) {:type (:type base)}
    :else {:type "number"}))

(defn query-input-schema
  "The collection query action's advertised input, generated from the
  filterable/sortable declarations. Keys are wire param names."
  [rdef]
  (let [state-prop {:type "string" :enum (mapv name (:states rdef))
                    :x-in true}
        props
        (reduce
         (fn [props [f ops]]
           (let [fname (name f)]
             (if (= :state f)
               (assoc props "state" state-prop)
               (let [{:keys [array?]} (field-info rdef f)
                     js (schema/json-schema (schema/field-schema (:schema rdef) f))
                     base (if (= "array" (:type js))
                            (or (:items js) {:type "string"})
                            (select-keys js [:type :format :enum]))]
                 (cond-> props
                   (or (:eq ops) (:in ops) array?)
                   (assoc fname (cond-> base
                                  (or (:in ops) array?) (assoc :x-in true)))
                   (:range ops)
                   (assoc (str fname "_gte") (bound-of base)
                          (str fname "_lte") (bound-of base))
                   (:after ops)
                   (assoc (str fname "_after")
                          {:type "string"
                           :format (or (:format base) "date-time")}))))))
         {} (sort-by (comp name key) (:filterable rdef)))
        props (cond-> props
                (not (contains? props "state")) (assoc "state" state-prop))
        sortable (get-in rdef [:sortable :fields])
        props (cond-> props
                (seq sortable)
                (assoc "sort"
                       (cond-> {:type "string"
                                :enum (vec (mapcat (fn [f] [(name f)
                                                            (str "-" (name f))])
                                                   sortable))}
                         (get-in rdef [:sortable :default])
                         (assoc :default (get-in rdef [:sortable :default])))))]
    {:type "object"
     :properties (assoc props
                        "page[size]" {:type "integer" :minimum 1
                                      :maximum page-size-max
                                      :default page-size-default}
                        "page[number]" {:type "integer" :minimum 1 :default 1})
     :additionalProperties false}))

;; ── the collection affordances ──────────────────────────────────────

(defn- bulk-input-schema
  "The declared action input, gaining the ids array — one input, N
  resources."
  [a max-items]
  (let [base (if (:input a)
               (schema/json-schema (:input a))
               {:type "object" :properties {}})]
    (-> base
        (update :properties assoc :ids {:type "array"
                                        :items {:type "string"}
                                        :minItems 1
                                        :maxItems max-items})
        (update :required (fnil conj []) "ids"))))

(defn- collection-actions [rdef]
  (let [base (str "/api/" (:plural rdef))
        model (or (:create-schema rdef) (:schema rdef))
        bulk-entries
        (into {}
              (keep (fn [a]
                      (when (:bulk a)
                        (let [spec (if (map? (:bulk a)) (:bulk a) {})
                              max-items (:max-items spec 100)]
                          [(:name a)
                           (cond-> {:method "POST"
                                    :href (str base "/-/" (name (:name a)))
                                    :input (bulk-input-schema a max-items)
                                    :effect (cond-> {:to (name (:to a)) :bulk true}
                                              (contains? (:terminal rdef) (:to a))
                                              (assoc :terminal true))
                                    :safety {:idempotent (boolean (get-in a [:safety :idempotent]))
                                             :reversible (boolean (get-in a [:safety :reversible]))
                                             :confirm (boolean (get-in a [:safety :confirm]))}}
                             (seq (:display a)) (assoc :display (:display a)))]))))
              (machine/actions-seq rdef))]
    (merge
     {:create {:method "POST"
               :href base
               :input (schema/json-schema model)
               :effect {:to (name (:initial rdef))}
               :safety {:idempotent false :reversible false :confirm false}}
      :query {:method "GET"
              :href base
              :input (query-input-schema rdef)}}
     bulk-entries)))

;; ── facets ──────────────────────────────────────────────────────────

(defn- facet-map
  "value→count per :faceted field under the applied conds — its own
  transaction, best-effort: a failed facet drops with a warning,
  never the page."
  [eng rdef conds]
  (let [st (:storage eng)]
    (not-empty
     (into {}
           (keep (fn [f]
                   (try
                     (let [array? (and (not= :state f)
                                       (:array? (field-info rdef f)))
                           counts (store/with-tx st
                                    #(store/facet-counts st % (:kind rdef)
                                                         f conds array?))]
                       (when (seq counts) [f counts]))
                     (catch Exception e
                       (binding [*out* *err*]
                         (println "waymark10 facet count failed for"
                                  (name (:kind rdef)) (name f) "-"
                                  (ex-message e)))
                       nil))))
           (:faceted rdef)))))

(defn- splice-facets
  "x-facets (and a dynamic enum when the property declares none) into
  the WIRED query schema — after the kebab→snake boundary, so facet
  value keys survive verbatim."
  [doc facets]
  (reduce-kv
   (fn [doc f counts]
     (let [path ["actions" "query" "input" "properties" (name f)]]
       (if (get-in doc path)
         (-> doc
             (assoc-in (conj path "x-facets") counts)
             (update-in path (fn [prop]
                               (if (contains? prop "enum")
                                 prop
                                 (assoc prop "enum" (vec (keys counts)))))))
         doc)))
   doc facets))

;; ── the envelope ────────────────────────────────────────────────────

(defn- enc ^String [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- page-href [plural applied size number]
  (str "/api/" plural "?"
       (str/join "&"
                 (map (fn [[k v]] (str (enc k) "=" (enc v)))
                      (assoc applied
                             "page[size]" size
                             "page[number]" number)))))

(defn- decode-row [rdef row]
  ;; inv/decode-row: coercion AND the shape fold (phase 8 upcasts)
  (inv/decode-row rdef row))

(defn envelope
  "The collection envelope for one parsed GET: parse the params (422
  on unknowns), page the rows, count the filtered total, fold the
  facets, and render items as envelope-minus-data summaries."
  [eng rdef params ctx-opts]
  (let [plural (:plural rdef)
        kname (name (:kind rdef))
        {:keys [conds sort page filters applied]} (parse-query rdef params)
        st (:storage eng)
        {:keys [rows total]}
        (store/with-tx st
          (fn [tx]
            {:rows (store/search-rows st tx (:kind rdef) conds
                                      {:order-by (:field sort)
                                       :desc (:desc sort)
                                       :limit (:size page)
                                       :offset (* (:size page)
                                                  (dec (:number page)))})
             :total (store/count-matching st tx (:kind rdef) conds)}))
        facets (facet-map eng rdef conds)
        items (mapv #(render/envelope-summary rdef (decode-row rdef %) ctx-opts)
                    rows)
        {size :size number :number} page
        last-page (max 1 (long (Math/ceil (/ (double total) (double size)))))
        self (if (and (empty? applied) (= 1 number))
               (str "/api/" plural)
               (page-href plural applied size number))
        summary (cond-> (str (str/capitalize kname) " · "
                             (count items) " of " total " shown")
                  (seq filters)
                  (str " · filtered: "
                       (str/join ", " (map (fn [[k v]] (str k "=" v)) filters))))
        page-link (fn [n] {:href (page-href plural applied size n)
                           :kind (str kname "_collection")
                           :summary (str "Page " n " of " last-page)})
        links (cond-> {}
                (< number last-page) (assoc :next (page-link (inc number)))
                (< 1 number) (assoc :prev (page-link (dec number))))
        doc (p/wire-value
             {:waymark "10"
              :kind (str kname "_collection")
              :self self
              :state "ok"
              :summary summary
              :data {:items items
                     :total total
                     :page {:size size :number number}}
              :actions (collection-actions rdef)
              :links links})]
    (splice-facets doc facets)))
