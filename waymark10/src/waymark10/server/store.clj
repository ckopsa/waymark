(ns waymark10.server.store
  "The storage seam: six operations and a transition append. Rows are
  maps {:id :state :version :data :shape :owner :law-revision
  :next-flip-at :created-at :updated-at}; :data is the plain-JSON
  document (keyword keys, exact decimals) — type coercion against the
  kind's schema is the engine's job at the load boundary, storage
  stays dumb.

  The transition log is one append-only table wearing four hats:
  audit trail, outbox (pg_notify rides the write transaction),
  activity feed, and the idempotency/natural-replay anchor.

  This namespace also owns the STORAGE PROJECTION of a declaration
  (kind-projection): the one description of a kind's table — engine
  columns, promoted generated columns, standard indexes — that feeds
  BOTH the DDL the backend emits (store.postgres) and the storage
  facet the fingerprint records (waymark10.fingerprint). Never two
  descriptions of one table."
  (:require [waymark10.schema :as schema]
            [waymark10.types :as t]))

(defprotocol Storage
  (with-tx* [st f]
    "Run (f tx) inside one transaction; the invoke algorithm is one
    call of this.")
  (ensure-kind! [st rmap]
    "Create the kind's table from its declaration when absent —
    additive only (CREATE IF NOT EXISTS); drift on an EXISTING table
    is the migrate planner's job (server.store.migrate), gated at
    boot.")
  (load-row [st tx kind id opts]
    "The row map, or nil. {:for-update true} takes the row lock —
    exactly one per invocation.")
  (insert-row! [st tx kind row])
  (save-row! [st tx kind row expected-version]
    "Optimistic save; throws :waymark10/version-conflict when the row
    moved.")
  (query-rows [st tx kind where opts]
    "Rows matching a {field value} equality map (phase-2 grammar;
    collections widen it in phase 7). opts: {:limit n :order-by kw}.")
  (append-transition! [st tx record]
    "Append to the log and notify the outbox channel in the same
    transaction. Returns the record with its assigned :id.")
  (transitions [st tx where opts]
    "Log rows: where {:kind … :resource-id … :since id}, newest-last.")
  (idempotency-lookup [st tx key kind]
    "→ {:status :response :media-type :request-digest} or nil.")
  (idempotency-store! [st tx key kind action digest status response media-type])
  (law-count [st tx kind revision]
    "How many rows of the kind are stamped with this law revision —
    the supersede-when-empty question (phase 5). Counts every stamped
    row, terminal included: a law lives while anything cites it.")
  (restamp-law! [st tx kind where to-revision]
    "Bulk-move a population's law stamp: rows matching the equality
    map ({field value}; :state and :law-revision address their
    columns, anything else the JSON document) get law_revision =
    to-revision. Version untouched and no transition appended —
    recorded deviation from waymark9, whose restamp logged per row:
    lifecycle restamps are maintenance, not writes. Returns the row
    count moved.")

  ;; ── phase 6: the maintainer's reads and the maintenance write ──────

  (count-matching [st tx kind conds]
    "COUNT of the kind's rows matching every cond — the maintainer's
    aggregate read. A cond is {:target :state|:id|:data, :field name,
    :cast sql-type, :op :=|:<|:<=|:>=|:>|:in, :value v | :values [vs]};
    :data values cross as strings and cast server-side.")
  (sum-matching [st tx kind of conds]
    "Exact SUM of the :of data field over the cond-matched rows (same
    grammar as count-matching) — the :sum aggregate's read (batch C's
    named follow-up, landed). Nil/absent fields don't contribute; the
    empty set sums to 0. → a BigDecimal; landing it in the fact's
    declared type is the maintainer's job.")
  (ids-matching [st tx kind conds limit]
    "Row ids matching every cond (same grammar as count-matching),
    id-ordered, LIMIT'd — the inverted dependent query and the
    backfill pager.")
  (update-data! [st tx kind id data next-flip-at]
    "The maintenance write: the document and the clock index only —
    version untouched, updated_at stamped, NO transition logged.
    Derivation maintenance is not a write (phase 6).")
  (due-flips [st tx kind now limit]
    "Rows whose next_flip_at <= now, oldest flip first, FOR UPDATE —
    the clock sweep's page.")

  ;; ── phase 7: the collection surface and the draft rows ─────────────

  (search-rows [st tx kind conds opts]
    "The collection page: rows matching every cond (the maintainer's
    grammar, widened with :op :in-any — vocab-array membership via
    JSONB containment). opts {:order-by field-kw :desc bool :limit n
    :offset n}; ordering runs over the promoted generated column
    (f_<field>), :state over its column, nil over created_at — id
    tiebreak always, so pages never overlap.")
  (facet-counts [st tx kind field conds array?]
    "Observed value → count for one faceted field under the same conds
    the rows match — a real GROUP BY. array? true unrolls a JSON array
    field (one row counts once per member).")
  (load-draft [st tx kind id action audience]
    "→ {:values … :base-version … :updated-at …} or nil.")
  (save-draft! [st tx kind id action audience values base-version]
    "Upsert the (kind, id, action, audience) draft row: values replace
    wholesale, base_version restamps, updated_at now.")
  (delete-draft! [st tx kind id action audience]
    "Discard/consume: delete the draft row; absent is a no-op.")

  ;; ── phase 9b: consumer cursors and job leases ──────────────────────

  (cursor-get [st tx consumer]
    "The named consumer's log position (a transition id), or nil when
    the consumer has never checkpointed.")
  (cursor-set! [st tx consumer position]
    "Upsert the consumer's cursor — the at-least-once checkpoint the
    webhook deliverer resumes from across restarts.")
  (claim-job-lease! [st tx job-id holder ttl-seconds]
    "Claim-or-steal the job's lease: insert, extend our own, or take
    over one whose expiry has passed — a live other holder refuses.
    → true when held for ttl-seconds from now.")
  (release-job-lease! [st tx job-id holder]
    "Drop the lease if we still hold it; absent or stolen is a no-op.")
  (job-lease [st tx job-id]
    "The job's lease row, {:holder … :expires-at inst} or nil — the
    orphan sweep's read (batch F): a :running job whose lease is
    absent or expired has no live claimant."))

(defn with-tx
  "Sugar: (with-tx st [tx] …)."
  [st f]
  (with-tx* st f))

(defn migratable?
  "Does this storage expose a live SQL schema the migrate planner can
  snapshot? The Postgres backend carries its datasource under :ds;
  the in-memory twin has no schema beyond ensure-kind!'s fresh
  tables, so drift cannot exist and the boot's migrate gate is
  vacuous. A duck-type on :ds today — promote to a protocol method
  when a third backend arrives."
  [storage]
  (some? (:ds storage)))

(defn version-conflict [kind id expected]
  (ex-info (str "version conflict: " (name kind) " " id
                " moved past v" expected)
           {:waymark10/version-conflict true :kind kind :id id
            :expected-version expected}))

(defn definition-checked-name
  "Identifiers spliced into SQL come only from checked declarations;
  refuse anything else loudly."
  ^String [x]
  (let [s (name x)]
    (when-not (re-matches #"[a-z][a-z0-9_]*" s)
      (throw (t/definition-error (str "identifier " (pr-str s) " is not a checked snake_case token"))))
    s))

;; ── the storage projection (the migrate planner's spine) ────────────
;; In v10 ALL row data lives in the JSONB document; every per-kind
;; column beyond the engine's fixed set is a GENERATED column derived
;; from it. The projection below is therefore the whole truth about a
;; kind's table, and the fingerprint's storage facet is this same map
;; canonicalized — a promotion change is law, and dropping/recreating
;; a promoted column is always data-safe.

(def engine-columns
  "The fixed columns every kind table carries, in DDL order. :type is
  the canonical short spelling the live snapshot normalizes to; :ddl
  is the full column clause."
  [{:name "id" :type "text" :ddl "id text PRIMARY KEY"}
   {:name "state" :type "text" :ddl "state text NOT NULL"}
   {:name "version" :type "bigint" :ddl "version bigint NOT NULL DEFAULT 1"}
   {:name "data" :type "jsonb" :ddl "data jsonb NOT NULL"}
   {:name "shape" :type "int" :ddl "shape int NOT NULL DEFAULT 1"}
   {:name "owner" :type "text" :ddl "owner text"}
   {:name "law_revision" :type "int" :ddl "law_revision int"}
   {:name "next_flip_at" :type "timestamptz" :ddl "next_flip_at timestamptz"}
   {:name "created_at" :type "timestamptz"
    :ddl "created_at timestamptz NOT NULL DEFAULT now()"}
   {:name "updated_at" :type "timestamptz"
    :ddl "updated_at timestamptz NOT NULL DEFAULT now()"}])

(defn generated-column-type
  "SQL type for a promoted filterable/sortable field, from its schema
  form; nil when the field has no single-value promotion (vocab
  arrays get GIN in phase 7)."
  [field-schema]
  (let [head (if (vector? field-schema) (first field-schema) field-schema)]
    (case head
      :waymark/date "date"
      :waymark/instant "timestamptz"
      :boolean "boolean"
      :int "bigint"
      (:double :decimal) "numeric"
      (:string :waymark/ref :waymark/vocab :enum) "text"
      nil)))

(defn- generated-expression
  "The extraction expression a promoted column derives by — mechanical
  from (field, type), which is why expression-only drift never needs
  detecting: the expression cannot move unless name or type did."
  [sql-type fname]
  (case sql-type
    "date" (str "waymark10_date(data->>'" fname "')")
    "timestamptz" (str "waymark10_ts(data->>'" fname "')")
    "boolean" (str "(data->>'" fname "')::boolean")
    "bigint" (str "(data->>'" fname "')::bigint")
    "numeric" (str "(data->>'" fname "')::numeric")
    (str "data->>'" fname "'")))

(defn- promoted-column [rmap field]
  (let [fname (definition-checked-name field)
        sql-type (generated-column-type
                  (schema/field-schema (:schema rmap) field))]
    (when sql-type
      {:name (str "f_" fname)
       :type sql-type
       :generated? true
       :ddl (str "f_" fname " " sql-type " GENERATED ALWAYS AS ("
                 (generated-expression sql-type fname) ") STORED")})))

(defn vocab-array-schema?
  "Is this field's schema form a vector of :waymark/vocab tokens (the
  membership-filtered shape with no single-value promotion)? :maybe
  wrapping and property maps unwrap."
  [field-schema]
  (let [s (if (and (vector? field-schema) (= :maybe (first field-schema)))
            (last field-schema)
            field-schema)]
    (boolean
     (and (vector? s) (= :vector (first s))
          (let [item (last s)]
            (= :waymark/vocab (if (vector? item) (first item) item)))))))

(defn- gin-indexes
  "A GIN index per vector-typed :waymark/vocab filterable field (batch
  F, closing phase 7's named punt): membership filters (:in-any, the
  jsonb ?| containment) walk the index instead of scanning. Vocab
  arrays still have no single-value promotion — the GIN entry is
  their whole storage story."
  [rmap table]
  (into {}
        (keep (fn [field]
                (when (and (not= :state field)
                           (vocab-array-schema?
                            (schema/field-schema (:schema rmap) field)))
                  (let [fname (definition-checked-name field)
                        ix (str "ix_" table "_" fname "_gin")]
                    [ix (str "CREATE INDEX IF NOT EXISTS " ix " ON " table
                             " USING gin ((data->'" fname "'))")]))))
        (sort (keys (:filterable rmap)))))

(defn kind-projection
  "The one projection of a kind's table: the engine's fixed columns,
  a generated column per promoted field (filterable ∪ sortable, phase
  7's rule — sort orders by the generated column, so sortable fields
  promote too; :state has its own column; vocab arrays have no
  single-value promotion), and the standard indexes — plus a GIN
  index per vocab-array filterable field (batch F).
  → {:table … :columns [{:name :type :generated? :ddl} …]
     :indexes {name create-sql}}"
  [rmap]
  (let [table (definition-checked-name (:plural rmap))
        promoted (keep (fn [field]
                         (when-not (= field :state)
                           (promoted-column rmap field)))
                       (sort (into (set (keys (:filterable rmap)))
                                   (get-in rmap [:sortable :fields]))))]
    {:table table
     :columns (into engine-columns promoted)
     :indexes
     (merge
      {(str "ix_" table "_state")
       (str "CREATE INDEX IF NOT EXISTS ix_" table "_state ON " table " (state)")
       (str "ix_" table "_law")
       (str "CREATE INDEX IF NOT EXISTS ix_" table "_law ON " table " (law_revision)")
       (str "ix_" table "_flip")
       (str "CREATE INDEX IF NOT EXISTS ix_" table "_flip ON " table
            " (next_flip_at) WHERE next_flip_at IS NOT NULL")}
      (gin-indexes rmap table))}))

(defn projection-snapshot
  "A projection reduced to the comparable shape the live snapshot
  reads back: columns by name with type + generated flag (expressions
  deliberately excluded — see kind-projection), index names to their
  creating SQL."
  [{:keys [columns indexes]}]
  {:columns (into {}
                  (map (fn [{col :name :keys [type generated?]}]
                         [col {:type type :generated? (boolean generated?)}]))
                  columns)
   :indexes indexes})
