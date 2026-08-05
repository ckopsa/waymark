(ns waymark10.server.collections
  "The collection surface (phase 7): GET /api/{plural} filtered by the
  declared :filterable grammar — field= (:eq), field=a,b (:in comma
  list), field_ne= (:ne, comma list negates as NOT IN), field_gte=/
  field_lte= (:range), field_after=/field_before= (:after/:before,
  strict bounds), field_set=true|false (:set, presence), field_contains=
  (:contains, case-insensitive substring), state= always, vocab-array
  membership via JSONB containment — ordered by
  the declared :sortable fields over their promoted columns, paged
  1-based (size cap 100, default 25). The envelope carries the items
  as envelope-minus-data summaries, the REAL filtered total, the
  create/query/bulk affordances (the query input schema is generated
  from the filterable/sortable declarations, with x-facets counts on
  :faceted fields and x-ref on ref fields — a filter by reference is
  a pick from the target's rows, not an id typed from memory), and
  next/prev links (omitted at the edges).

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
            [waymark10.checks :as checks]
            [waymark10.machine :as machine]
            [waymark10.saved-view :as sv]
            [waymark10.schema :as schema]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def page-size-default 25)
(def page-size-max 100)

;; ── field typing ────────────────────────────────────────────────────

(defn- field-info [rdef f]
  (let [s (schema/field-schema (:schema rdef) f)]
    {:head (schema/leaf-head s)
     :array? (boolean (and (vector? s) (= :vector (first s))))}))

(defn- cast-of [head]
  (case head
    :waymark/date "date"
    :waymark/instant "timestamptz"
    :int "bigint"
    :boolean "boolean"
    (:double :decimal) "numeric"
    "text"))

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
                      (when (:ne ops)
                        [[(str fname "_ne") (assoc e :mode :ne :in? true)]])
                      (when (:range ops)
                        [[(str fname "_gte") (assoc e :mode :gte)]
                         [(str fname "_lte") (assoc e :mode :lte)]])
                      (when (:after ops)
                        [[(str fname "_after") (assoc e :mode :after)]])
                      (when (:before ops)
                        [[(str fname "_before") (assoc e :mode :before)]])
                      (when (:set ops)
                        [[(str fname "_set") (assoc e :mode :set)]])
                      (when (:contains ops)
                        [[(str fname "_contains")
                          (assoc e :mode :contains)]]))))))
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
      (and (:array? e) (= :eq (:mode e)))
      {:target :data :field f :op :in-any :values values}
      :else
      (let [cast (cast-of (:head e))
            base {:target :data :field f :cast cast}]
        (case (:mode e)
          :eq (if multi?
                (assoc base :op :in :values values)
                (assoc base :op := :value v))
          :ne (if multi?
                (assoc base :op :not-in :values values)
                (assoc base :op :not= :value v))
          :gte (assoc base :op :>= :value v)
          :lte (assoc base :op :<= :value v)
          :after (assoc base :op :> :value v
                        :cast (if (= "date" cast) "date" "timestamptz"))
          :before (assoc base :op :< :value v
                         :cast (if (= "date" cast) "date" "timestamptz"))
          ;; presence and substring read the raw text, never the cast
          :set (assoc base :op :set? :value (= "true" v) :cast "text")
          :contains (assoc base :op :contains :value v :cast "text"))))))

(defn default-filter-params
  "The declared :default-filters as wire params — {param value} for
  every default whose FIELD the caller named no filter on. Explicit
  beats default always: what suppresses the substitution is the
  caller MENTIONING the field, not the value they gave it, so
  ?state= (empty) clears the default instead of re-substituting it,
  and a filter on another field leaves it standing. g is the kind's
  grammar, which is what maps a param name (state, party_gte) back to
  the field it filters."
  [rdef g params]
  (let [named (into #{} (keep (fn [[pname _]] (:field (get g pname)))) params)]
    (into {}
          (keep (fn [[f v]] (when-not (contains? named f) [(name f) (str v)])))
          (:default-filters rdef))))

(defn parse-query
  "Query params ({string string}) → {:conds […] :sort {:field :desc}
  :page {:size :number} :filters sorted-map :applied sorted-map}, or
  one 422 problem naming every unknown/malformed parameter.

  A declared :default-filters value lands as if the caller had sent
  it — in the conds, in :filters (so the envelope's summary echoes it)
  and in :applied (so the self href a person copies is the view they
  saw); the conds it contributes carry :default? true, the one mark
  that separates the kind's own choice from the client's. Opts
  {:defaults? false} turns that off for the two callers that parse a
  query without serving a view: the embedded-collection splice (whose
  href is the parent's, and cannot carry a default the parent never
  advertised — the spec's named punt) and the pilot population
  grammar (where one param must compile to exactly one cond).

  An explicitly empty value on a filter param (?state=) filters
  nothing rather than answering 422 — it is how a client says 'this
  field, deliberately unfiltered', which is the only way to clear a
  default. A blank sort= is still a 422: sort hides no rows, so there
  is nothing to clear."
  ([rdef params] (parse-query rdef params nil))
  ([rdef params {:keys [defaults?] :or {defaults? true}}]
   (let [g (grammar rdef)
         sortable (mapv name (get-in rdef [:sortable :fields]))
         default-sort (get-in rdef [:sortable :default])
         defaults (if defaults? (default-filter-params rdef g params) {})
         params (merge params defaults)
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
                (if (str/blank? raw)
                  acc                  ; the explicit clear: no cond, no echo
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
                                              (= :set (:mode e))
                                              (when-not (contains? #{"true" "false"} v)
                                                "must be true or false")
                                              ;; substring search takes any text,
                                              ;; whatever the field's own type
                                              (= :contains (:mode e)) nil
                                              (and (#{:after :before} (:mode e))
                                                   (not= :waymark/date (:head e)))
                                              (schema/filter-value-problem
                                               :waymark/instant v)
                                              :else (schema/filter-value-problem
                                                     (:head e) v))))
                                    (distinct))
                                   values)]
                    (cond
                      (empty? values)
                      (update acc :errors assoc pname ["must carry at least one value"])

                      (seq errs) (update acc :errors assoc pname errs)

                      :else (-> acc
                                (update :conds conj
                                        (cond-> (cond-of e values)
                                          (contains? defaults pname)
                                          (assoc :default? true)))
                                (update :filters assoc pname raw)))))
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
                                 :desc (str/starts-with? default-sort "-")})))))))

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
  filterable/sortable declarations. Keys are wire param names.

  A :default-filters entry rides its param's :default exactly as the
  sort default already rides sort's — the advertisement is half of
  what keeps a hiding filter from being invisible (the other halves
  are the self href and the rendered chip), and it is what tells a
  client that clearing this field means sending it empty rather than
  dropping it."
  [rdef]
  (let [state-prop {:type "string" :enum (mapv name (:states rdef))
                    :x-in true}
        entries (schema/entry-map (:schema rdef))
        props
        (reduce
         (fn [props [f ops]]
           (let [fname (name f)]
             (if (= :state f)
               (assoc props "state" state-prop)
               (let [{:keys [array?]} (field-info rdef f)
                     js (schema/json-schema (schema/field-schema (:schema rdef) f))
                     ;; a ref FILTERS the way it edits: the param carries
                     ;; the field's own x-ref, so the filter offers the
                     ;; target's rows by label instead of asking a human
                     ;; to type an id (the picker's declaration, read
                     ;; twice — form and filter)
                     xref (schema/ref-props (get-in entries [f :properties]))
                     base (cond-> (if (= "array" (:type js))
                                    (or (:items js) {:type "string"})
                                    (select-keys js [:type :format :enum]))
                            xref (assoc :x-ref xref))]
                 (cond-> props
                   (or (:eq ops) (:in ops) array?)
                   (assoc fname (cond-> base
                                  (or (:in ops) array?) (assoc :x-in true)))
                   (:ne ops)
                   (assoc (str fname "_ne") (assoc base :x-in true))
                   (:range ops)
                   (assoc (str fname "_gte") (bound-of base)
                          (str fname "_lte") (bound-of base))
                   (:after ops)
                   (assoc (str fname "_after")
                          {:type "string"
                           :format (or (:format base) "date-time")})
                   (:before ops)
                   (assoc (str fname "_before")
                          {:type "string"
                           :format (or (:format base) "date-time")})
                   (:set ops)
                   (assoc (str fname "_set") {:type "boolean"})
                   (:contains ops)
                   (assoc (str fname "_contains") {:type "string"}))))))
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
                         (assoc :default (get-in rdef [:sortable :default])))))
        props (reduce-kv (fn [props f v]
                           (let [pname (name f)]
                             (cond-> props
                               (contains? props pname)
                               (assoc-in [pname :default] (str v)))))
                         props
                         (:default-filters rdef))]
    {:type "object"
     :properties (assoc props
                        "page[size]" {:type "integer" :minimum 1
                                      :maximum page-size-max
                                      :default page-size-default}
                        "page[number]" {:type "integer" :minimum 1 :default 1})
     :additionalProperties false}))

(defn drop-filter-defaults
  "The advertised query schema with its default-filter advertisement
  removed — what an EMBED publishes as its columns. The embedded
  splice parses with {:defaults? false}, so advertising a default it
  will not apply would be a client-visible lie; sort's default and the
  page defaults stay, because those DO apply there."
  [rdef qs]
  (update qs :properties
          (fn [props]
            (reduce (fn [props f]
                      (cond-> props
                        (contains? props (name f))
                        (update (name f) dissoc :default)))
                    props
                    (keys (:default-filters rdef))))))

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
               ;; the secret disposition (waymark-kyg): the door
               ;; accepts a secret field; the ADVERTISED create input
               ;; never names it — union over both spellings, since a
               ;; separate :create-schema may carry the mark itself
               :input (schema/conceal (schema/json-schema model)
                                      (into (schema/secret-fields (:schema rdef))
                                            (schema/secret-fields model)))
               :effect {:to (name (:initial rdef))}
               :safety {:idempotent false :reversible false :confirm false}}
      :query {:method "GET"
              :href base
              :input (query-input-schema rdef)}}
     bulk-entries)))

(defn- action-label
  "The human label a gesture wears: the bound action's declared
  display label, else the name humanized (the render layer's own
  fallback, read twice)."
  [rdef aname]
  (or (get-in rdef [:actions aname :display :label])
      (str/replace (name aname) "_" " ")))

(defn- view-entries
  "The declared :views, advertised: name/kind as wire tokens, :where
  as the query params a client appends (already normalized to wire
  strings, the :default-filters discipline), :card as the field
  subset, and — for a :deck — the two gestures, each naming its bound
  action and that action's human label. The view is presentation: a
  client still finds the actual affordance per-item in item.actions,
  so grants and state keep gating."
  [rdef]
  (mapv (fn [v]
          (cond-> {:name (:name v) :kind (:kind v)}
            (seq (:where v)) (assoc :where (:where v))
            (seq (:card v)) (assoc :card (mapv name (:card v)))
            (= :deck (:kind v))
            (assoc :gestures
                   {:right {:action (:right v)
                            :label (action-label rdef (:right v))}
                    :left {:action (:left v)
                           :label (action-label rdef (:left v))}})
            (seq (:display v)) (assoc :display (:display v))))
        (:views rdef)))

(defn- saved-view-entry
  "One ACTIVE saved_view row as a wire view entry on rdef's own
  envelope — the declared entries' exact shape plus {:source \"saved\"}
  and the row's self href (the client's edit-this-view affordance);
  the wire :name is minted from the row id (\"sv-<id>\"), so it never
  collides with a declared snake token and survives a relabel. nil
  (with a *err* warning) when the row no longer validates against the
  CURRENT declaration — a redeploy can strand a saved gesture or
  field, and a stale view must not break the collection page; the row
  stays visible in the saved_views collection itself, where its owner
  can fix or retire it."
  [rdef self-href row]
  (let [data (:data row)
        view (sv/view-of data)]
    (if-some [ps (seq (checks/view-problems rdef view))]
      (do (binding [*out* *err*]
            (println "waymark10 saved view" (:id row) "skipped for"
                     (name (:kind rdef)) "-" (first ps)))
          nil)
      (cond-> {:name (str "sv-" (:id row))
               :kind (:kind view)
               :source "saved"
               :href self-href
               :display {:label (:label data)}}
        (seq (:where view)) (assoc :where (:where view))
        (seq (:card view)) (assoc :card (mapv name (:card view)))
        (= :deck (:kind view))
        (assoc :gestures
               {:right {:action (:right view)
                        :label (action-label rdef (:right view))}
                :left {:action (:left view)
                       :label (action-label rdef (:left view))}})))))

(defn- saved-view-entries
  "The ACTIVE saved_view rows targeting this kind (by kind name or
  plural), advertised beside the declared views. Costs one store
  query, and only when the registry hosts the saved_view kind —
  membership checked against sv/kind, the definite marker, never a
  name string. Best-effort like facet-map: a failed read drops the
  merge with a *err* warning, never the page."
  [eng rdef]
  (when-some [sv-rdef (get (inv/resources eng) sv/kind)]
    (try
      (let [st (:storage eng)
            rows (store/with-tx st
                   (fn [tx]
                     (store/search-rows
                      st tx sv/kind
                      [{:target :state :op := :value "active"}
                       {:target :data :field :target :cast "text" :op :in
                        :values [(name (:kind rdef)) (:plural rdef)]}]
                      {:limit 50 :offset 0})))]
        (into []
              (keep (fn [row]
                      (let [row (inv/decode-row sv-rdef row)]
                        (saved-view-entry
                         rdef
                         (str "/api/" (:plural sv-rdef) "/" (:id row))
                         row))))
              rows))
      (catch Exception e
        (binding [*out* *err*]
          (println "waymark10 saved views merge failed for"
                   (name (:kind rdef)) "-" (ex-message e)))
        nil))))

;; ── facets ──────────────────────────────────────────────────────────

(defn- facet-map
  "value→count per :faceted field under the applied conds MINUS the
  field's own (standard faceting: picking a value must not collapse
  the field's other options to zero — the showcase select and the
  facet chips both need the road back) — its own transaction,
  best-effort: a failed facet drops with a warning, never the page."
  [eng rdef conds]
  (let [st (:storage eng)]
    (not-empty
     (into {}
           (keep (fn [f]
                   (try
                     (let [array? (and (not= :state f)
                                       (:array? (field-info rdef f)))
                           ;; the leash cond (:vis?, grants/conds-of) is
                           ;; never "the field's own filter" — stripping
                           ;; it counted the whole kind past the grant
                           own? (fn [c] (and (not (:vis? c))
                                             (if (= :state f)
                                               (= :state (:target c))
                                               (= f (:field c)))))
                           counts (store/with-tx st
                                    #(store/facet-counts st % (:kind rdef)
                                                         f (remove own? conds)
                                                         array?))]
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
  facets, and render items as envelope-minus-data summaries. A
  :visibility in ctx-opts (phase 9a, the per-request grant projection)
  narrows an id-scoped grant's page to its granted rows (a real cond,
  so the total stays honest) and drops non-granted create/bulk
  affordances; items project through render's own visibility filter."
  [eng rdef params ctx-opts]
  (let [plural (:plural rdef)
        kname (name (:kind rdef))
        {:keys [conds sort page filters applied]} (parse-query rdef params)
        vis (:visibility ctx-opts)
        ;; the collection oracle, closed (waymark-rci): REQUESTED
        ;; filters and sorts naming non-plain fields answer the
        ;; unknown-param 422; the kind's own default sort softly
        ;; falls back to id order instead — the client asked nothing.
        ;; A default FILTER is the kind's own choice the same way, so
        ;; its cond is exempt too: the oracle exists to refuse a
        ;; client's probe, and the declaration is not a probe.
        _ (grants/check-query! vis rdef (remove :default? conds)
                               (when (contains? params "sort")
                                 (:field sort)))
        sort (if (and vis (:field sort)
                      (not (#{:id :state} (:field sort)))
                      (not (grants/plain-field? vis (:kind rdef)
                                                (:field sort))))
               ;; natural order — never the promoted column a scoped
               ;; reader may not see plain
               (assoc sort :field nil)
               sort)
        conds (if-some [ids (when vis ((:ids-of vis) (:kind rdef)))]
                (conj conds {:target :id :op :in :values (vec ids)})
                conds)
        ;; a filter-scoped grant narrows the query the same way it
        ;; narrows row? — page, total and facets stay one story
        conds (if-some [fconds (when (and vis (:conds-of vis))
                                 ((:conds-of vis) (:kind rdef)))]
                (into conds fconds)
                conds)
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
                (< 1 number) (assoc :prev (page-link (dec number)))
                ;; the offline round-trip, carrying THIS view's filters
                ;; — the downloaded workbook holds exactly what the
                ;; page's query shows (unpaged; the worksheet route
                ;; ignores pagination). The export PROJECTS under a
                ;; grant (waymark-ecq closed), so a scoped reader gets
                ;; the button back — but the upload half still refuses
                ;; scoped requests (staging lands rows the uploader
                ;; cannot see), so the scoped summary promises only
                ;; the download
                (:worksheet rdef)
                (assoc :worksheet
                       (let [q (dissoc applied "page[size]" "page[number]")]
                         {:href (str "/api/" plural "/-/worksheet"
                                     (when (seq q)
                                       (str "?" (str/join "&"
                                                          (map (fn [[k v]]
                                                                 (str (enc k) "=" (enc v)))
                                                               q)))))
                          :kind "worksheet"
                          :summary (if vis
                                     "This view as a workbook download"
                                     "This view as an editable workbook — download, edit offline, upload the edits")})))
        acts (collection-actions rdef)
        ;; a scoped request's collection affordances: query stays (the
        ;; kind itself is granted or this envelope never rendered),
        ;; create and bulk entries survive only when granted
        acts (if vis
               (into {} (filter (fn [[aname _]]
                                  (or (= :query aname)
                                      ((:action? vis) (:kind rdef) aname))))
                     acts)
               acts)
        ;; the alternate views: the declared entries plus the ACTIVE
        ;; saved_view rows targeting this kind (views as resources,
        ;; waymark-rla) — one wire shape, saved entries marked
        ;; {:source "saved"} and carrying their own href
        views (into (view-entries rdef) (saved-view-entries eng rdef))
        doc (p/wire-value
             (cond-> {:waymark "10"
                      :kind (str kname "_collection")
                      :self self
                      :state "ok"
                      :summary summary
                      :data {:items items
                             :total total
                             :page {:size size :number number}}
                      :actions acts
                      :links links}
               ;; the declared+saved alternate views, advertised beside
               ;; the actions/links — absent when neither exists
               (seq views) (assoc :views views)))]
    (splice-facets doc facets)))
