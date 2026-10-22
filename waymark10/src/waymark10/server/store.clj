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
  (:require [clojure.string :as str]
            [waymark10.schema :as schema]
            [waymark10.types :as t])
  (:import (java.time DayOfWeek Instant LocalDate ZoneOffset)
           (java.time.temporal TemporalAdjusters)))

;; ── a purge's reach into the log (docs/spec-documents.md §5, 4b) ────

(def purged-value
  "What a value a purge cleared reads as in the row's past log."
  "‹purged›")

(defn- purge-map
  "`m` with each set value under a name in `fields` read as
  purged-value, under either key spelling a JSON round-trip leaves."
  [m fields]
  (if (map? m)
    (reduce (fn [m k] (cond-> m (some? (get m k)) (assoc k purged-value)))
            m (mapcat (juxt keyword name) fields))
    m))

(defn- purge-guard
  "One guard's line in a retained judgment: each evidence value whose
  form read one of `fields` (its read_fields) reads as purged-value.
  The var names, the verdict and the reads stay."
  [g fields]
  (let [names (set (map name fields))
        hit (keep (fn [[var' fs]] (when (some #(names (name %)) fs) var'))
                  (:read_fields g))]
    (cond-> g
      (and (seq hit) (map? (:read g)))
      (update :read #(reduce (fn [r v] (cond-> r (contains? r v) (assoc v purged-value)))
                             % hit)))))

(defn redact-transition
  "One log row with every value of `fields` read as purged-value: in
  its inputs, its retained judgment's evidence and its retained
  document. Field names, actors, times and states stay. Pure — both
  stores' redact-log! write what it answers."
  [rec fields]
  (if (empty? fields)
    rec
    (cond-> rec
      (:inputs rec) (update :inputs purge-map fields)
      (:after rec) (update :after purge-map fields)
      (seq (get-in rec [:judgment :guards]))
      (update-in [:judgment :guards] (fn [gs] (mapv #(purge-guard % fields) gs))))))

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
    collections widen it in phase 7). A value spelled {:has v} is
    containment instead: the field is a JSON array holding the string
    v (a listed ref's ids). opts: {:limit n :order-by kw
    :newest-first bool}. Ordering is ASCENDING by created_at (or
    :order-by) unless :newest-first flips it — the spelling
    `transitions` already uses, so a bounded window can hold the
    fresh end of a long table instead of its oldest rows.")
  (external-ids [st tx kind]
    "Every stored row's external_id (nil-free, unordered) — the
    discovery diff's ONE set-based read. A per-id probe loop here
    was most of a six-figure kind's pass wall-clock; the whole
    point of this method is that it never becomes N queries.")
  (append-transition! [st tx record]
    "Append to the log and notify the outbox channel in the same
    transaction. Returns the record with its assigned :id.

    :judgment is the decision record (spec-decision-record) — the
    evidence the guards read, present only for a kind declaring
    :retain {:judgment true}, and nil everywhere else rather than an
    empty object that would lie about coverage. Which guards judged
    needs no column: it derives from :law-revision through
    waymark10.server.decision/basis.

    :after is time travel's tier 3 — the document as the write left
    it, present only for a kind declaring :retain {:data true}
    (waymark10.server.decision/after-record), nil everywhere else.")
  (transitions [st tx where opts]
    "Log rows: where {:kind … :resource-id … :since id}, newest-last.
    opts {:settled true} is for a reader that keeps a cursor: it
    answers only rows under every append still in flight, so no lower
    id can commit after the rows it returns. It blocks no writer. It
    answers nil — no rows, and not the [] of a log with none to give —
    when it has no row to give and the writers in flight under it do
    not finish in time: the reader asks again on its next pass, and
    one with no next pass asks through settled-transitions. A
    :newest-first read answers the newest row UNDER the appends in
    flight, not the newest committed. Such a read runs in a
    transaction of its own.")
  (transitions-under-grant [st tx grant-id since until opts]
    "Log rows whose actor carries this grant (actor->>'grant') and
    whose `at` lies in [since, until] — a nil bound is open. A
    sitting's transitions are these, over its grant and its window
    (ticket 39b2c934). Oldest first; opts {:limit n}, default 500.")
  (transition-stats [st tx since include-system?]
    "Weekly rhythm buckets over the log — the seasons door's one
    aggregate read: rows {:week-start inst :kind str :action str
    :actor-type str :n long}, grouped by UTC ISO week (utc-week-start)
    × kind × action × actor type, at >= since only. include-system?
    false drops rows whose actor type is system — the mirror-sync
    beat would otherwise dominate every count.")
  (transition-times [st tx kind action since until conds limit]
    "The `at` of each logged transition of `action` on `kind` whose at
    lies in [since, until) and whose row still matches every cond
    (count-matching's grammar) — a dashboard measure's
    transition:<action> read (dashboard measures 2/3). ONE query for
    the kind however many rows or buckets: ix_wm10_t_at bounds the
    window, and the conds narrow the kind's table as one semi-join.
    Oldest first, at most `limit` Instants.")
  (corrections-by-model [st tx actor-ids since excluded-kinds]
    "The correction count over the log, grouped by the model that was
    corrected — the ladder's one new query (spec-seat.md R-11.3).
    `excluded-kinds` (kind names as strings) are never corrections: the
    framework's own kinds, where a person's approve after a sitter's
    ask, or a close after a sitter's create, is bookkeeping and not a
    reversal of anything the sitter judged.

    A CORRECTION is a person's transition on a row whose IMMEDIATELY
    PREVIOUS transition on that same (kind, resource_id) was written
    by one of `actor-ids` — the members that sat the seat in the
    window. A write a person ALLOWED — a held call's replay, whose
    actor carries `allowed_by` (invoke/actor-map) — is that person's
    transition: it counts as a correction when it follows the seat's
    own, and a transition after it corrects nothing of the seat's. So
    this is a window function over the log, LAG by (kind,
    resource_id) ordered by id, and it is the one question the other
    five cannot be asked as a row read: nothing on a row records that
    somebody undid an agent.

    The lag runs over the WHOLE log and the `at >= since` filter is
    applied after it, because the transition being corrected is
    routinely older than the window that counts the correction.

    `actor-ids` empty → no rows. → [{:model str-or-nil :n long} …],
    where :model is the corrected transition's own actor model claim
    (absent on a hand that declared none, which is a nil key rather
    than a dropped row).")

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
    :data values cross as strings and cast server-side. One more op
    names a second kind, the anti-join: {:op :no-row, :target :id|:data,
    :field name, :from {:kind k, :target :id|:data, :field name, :conds
    [conds]}} keeps the row when NO row of k under those conds carries
    this row's id (or its field's text) in the named place. The
    collection's four reads answer it: count-matching, ids-matching,
    search-rows and facet-counts.")
  (sum-matching [st tx kind of conds]
    "Exact SUM of the :of data field over the cond-matched rows (same
    grammar as count-matching) — the :sum aggregate's read (batch C's
    named follow-up, landed). SQL SUM semantics, un-coalesced:
    nil/absent fields don't contribute, and a sum with NO
    contributions — zero matching rows, or matches whose :of are all
    nil — answers nil. → a BigDecimal or nil; landing it (and the
    declaration's empty default: 0, or absent under {:when-empty
    :absent}) in the fact's declared type is the maintainer's job.")
  (ids-matching [st tx kind conds limit]
    "Row ids matching every cond (same grammar as count-matching),
    id-ordered, LIMIT'd — the inverted dependent query and the
    backfill pager.")
  (update-data! [st tx kind id data next-flip-at]
    "The maintenance write: the document and the clock index only —
    version untouched, updated_at stamped, NO transition logged.
    Derivation maintenance is not a write (phase 6).")
  (delete-rows! [st tx kind ids]
    "Delete these rows of the kind outright — no transition, no
    tombstone. The one caller is a record kind's retention purge
    (server/transcripts): a transcript's lines past their seat's
    `transcript_days` go, and the transcript row keeps each file's
    count and chain as the proof of what existed. → the number
    deleted.")
  (redact-log! [st tx kind id fields]
    "A purge's reach into the past (docs/spec-documents.md §5, 4b):
    every logged transition of this one row rewritten through
    redact-transition, so each value of `fields` reads \"‹purged›\" in
    its stored inputs, retained judgment and retained document. Field
    names, actors, times and states stay; no transition is logged.
    → the number of log rows rewritten.")
  (due-flips [st tx kind now limit]
    "Rows whose next_flip_at <= now, oldest flip first, FOR UPDATE —
    the clock sweep's page.")

  ;; ── phase 7: the collection surface and the draft rows ─────────────

  (search-rows [st tx kind conds opts]
    "The collection page: rows matching every cond (the maintainer's
    grammar, widened with :op :in-any — vocab-array membership via
    JSONB containment). opts {:order-by field-kw :desc bool :then-by
    [{:field kw :desc bool} …] :limit n :offset n}; ordering runs over
    the promoted generated column (f_<field>), :state over its column,
    a sortable-timestamp over the engine column of that name, nil over
    created_at — then each :then-by key in order among the ties, and id
    tiebreak always, so pages never overlap.")
  (facet-counts [st tx kind field conds array? absent-as]
    "Observed value → count for one faceted field under the same conds
    the rows match — a real GROUP BY. array? true unrolls a JSON array
    field (one row counts once per member). absent-as, when not nil, is
    the value a scalar field's absent rows count under (the kind's
    :absent-as); nil leaves them uncounted.")
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
    absent or expired has no live claimant.")

  ;; ── waymark-db9.4: the elected singleton ───────────────────────────

  (elect-role! [st role-name opts]
    "Elect ONE holder of a named singleton role across every process
    sharing this storage, and run the role there. opts: :start-fn
    (run on the holder → an opaque handle), :stop-fn (called with
    that handle when the role is released or the hold is lost), and
    :retry-ms (the contention/liveness cadence). Returns the role
    handle release-role! takes; :held? and :starts are readable atoms.

    THIS METHOD IS WHY THE LIFECYCLE SEAM'S `:elected` FLAG IS
    HONEST. Election is 'one holder per database', and only the
    storage knows whether there IS a database with peers: Postgres
    spells it pg_advisory_lock on a dedicated session, and the
    in-memory twin has nobody to contend with, so it elects itself at
    once and `:elected` degrades to a plain start. The alternative —
    a core namespace asking `migratable?` and no-op'ing — leaves the
    twin advertising a surface it never runs. Same precedent as the
    boot's migrate gate, one level up: ask the storage what it can
    do, do not guess from its type.")

  (release-role! [st handle]
    "Release an elected role: the holder's stop-fn runs and its
    election session closes before this returns, so a peer acquires
    within one retry interval. A crashed process releases the same
    way — its sessions die with it."))

(defn with-tx
  "Sugar: (with-tx st [tx] …)."
  [st f]
  (with-tx* st f))

(defn stamped
  "Storage `st` stamping its rows' created_at and updated_at, and the
  log's `at`, from `now-fn` instead of its own clock: the demo clock
  `clock-shift/with-clock` lays over a clocked engine, so a filmed
  week's rows carry the week's own dates. A working engine's storage is
  never stamped, and its stamps stay the database's."
  [st now-fn]
  (assoc st :stamp-fn now-fn))

(defn stamp-of
  "The instant a write of storage `st` is stamped with when it is
  `stamped`; nil otherwise, and the store's own clock stamps it."
  ^Instant [st]
  (when-some [f (:stamp-fn st)] (f)))

(defn settled-transitions
  "The settled log (transitions' {:settled true}) for a reader with no
  next pass of its own: it asks up to `tries` times, each in a
  transaction of its own. → the rows, or nil when the writers in flight
  outlasted every try. The caller says what a log that never settled
  means for it; it is never an empty log."
  [st where opts tries]
  (loop [left (long tries)]
    (when (pos? left)
      (if-some [rows (with-tx st #(transitions st % where (assoc opts :settled true)))]
        rows
        (recur (dec left))))))

(defn utc-week-start
  "The UTC ISO week bucket an instant falls in — Monday 00:00 UTC, as
  an Instant. The ONE truncation transition-stats' buckets share
  across backends: Postgres spells it date_trunc('week', at AT TIME
  ZONE 'UTC'); the memory twin and the seasons reader call this."
  ^Instant [^Instant at]
  (-> (LocalDate/ofInstant at ZoneOffset/UTC)
      (.with (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY))
      (.atStartOfDay ZoneOffset/UTC)
      (.toInstant)))

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

(defn unique-violation
  "A declared :unique group refused this write at the index (design
  §24) — the honest 409's carrier, mirroring version-conflict."
  [kind id constraint]
  (ex-info (str "unique conflict: " (name kind)
                (when id (str " " id))
                " — a row with these values already exists"
                (when constraint (str " (" constraint ")")))
           {:waymark10/unique-violation true :kind kind :id id
            :constraint constraint}))

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

(def sortable-timestamps
  "The two engine columns a :sortable declaration may name besides its
  own schema fields. They are not schema entries — they have no
  promoted f_ column, no JSON-Schema type and no filter grammar — so
  every reader of a sortable field asks this set first: the projection
  skips promoting them, the stores order by the table column itself,
  and the declaration gate refuses a schema field that would shadow
  one. Recorded punt: on a MIRROR kind created_at is when the local
  row was minted, not when the authority created the thing; the
  mirror kinds that care already carry their own timestamp field."
  #{:created_at :updated_at})

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
      (:double :decimal :waymark/money) "numeric"
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

(defn- promoted-columns
  "The generated columns one promoted field projects: one, or for a
  :waymark/money field two (docs/spec-ledger-money.md §2) — its amount
  as f_<field> numeric, which filters and sorts read, and its currency
  as f_<field>_currency char(3)."
  [rmap field]
  (let [fname (definition-checked-name field)
        fschema (schema/field-schema (:schema rmap) field)
        sql-type (generated-column-type fschema)
        column (fn [cname type ddl-type expression]
                 {:name cname
                  :type type
                  :generated? true
                  :ddl (str cname " " ddl-type " GENERATED ALWAYS AS ("
                            expression ") STORED")})]
    (cond
      (= :waymark/money (if (vector? fschema) (first fschema) fschema))
      ;; information_schema spells char(3) "character"
      [(column (str "f_" fname) "numeric" "numeric"
               (str "(data->'" fname "'->>'amount')::numeric"))
       (column (str "f_" fname "_currency") "character" "char(3)"
               (str "(data->'" fname "'->>'currency')::char(3)"))]
      sql-type
      [(column (str "f_" fname) sql-type sql-type
               (generated-expression sql-type fname))])))

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

(defn- btree-indexes
  "A btree index per promoted filterable field (ticket 68ebaa0f): by
  default a field filterable by :eq or :in carries one, since equality
  and any-of are what it serves, and any other field carries none. The
  kind's :index {field :btree|:none} says otherwise for one field. A
  field whose own one-field :unique group indexes it takes no second."
  [rmap table promoted]
  (let [cols (set (map :name promoted))
        uniq (into #{}
                   (keep (fn [g]
                           (cond (keyword? g) g
                                 (= 1 (count g)) (first g))))
                   (:unique rmap))]
    (into {}
          (keep (fn [[field ops]]
                  (let [fname (definition-checked-name field)
                        col (str "f_" fname)
                        want (get (:index rmap) field
                                  (if (some #{:eq :in} ops) :btree :none))]
                    (when (and (= :btree want)
                               (contains? cols col)
                               (not (contains? uniq field)))
                      (let [ix (str "ix_" table "_f_" fname)]
                        [ix (str "CREATE INDEX IF NOT EXISTS " ix " ON " table
                                 " (" col ")")])))))
          (sort-by key (:filterable rmap)))))

(defn kind-projection
  "The one projection of a kind's table: the engine's fixed columns,
  a generated column per promoted field (filterable ∪ sortable, phase
  7's rule — sort orders by the generated column, so sortable fields
  promote too; :state has its own column; vocab arrays have no
  single-value promotion; the sortable-timestamps promote nothing,
  being engine columns already), and the standard indexes — plus a GIN
  index per vocab-array filterable field (batch F), and a btree index
  per promoted :eq/:in filterable field its :index does not decline.

  ix_<table>_created and ix_<table>_updated land on the kinds whose
  :sortable NAMES that timestamp, and nowhere else — a recorded
  deviation from docs/spec-collection-defaults.md, which asked for
  both as standard per-kind indexes. Standard would have moved every
  kind's storage facet, and with it every kind's fingerprint: one
  feature, a new law revision for every kind in every app at the next
  boot. Whether the unsorted page's own created_at ordering deserves
  an index everywhere is a real and separate question — measure it,
  then decide. Neither index carries the id tiebreak search-rows
  appends, so a large page still sorts within ties; a composite index
  is the measured follow-up, not a guess.
  → {:table … :columns [{:name :type :generated? :ddl} …]
     :indexes {name create-sql}}"
  [rmap]
  (let [table (definition-checked-name (:plural rmap))
        sortable (set (get-in rmap [:sortable :fields]))
        promoted (mapcat (fn [field]
                           (when-not (or (= field :state)
                                         (contains? sortable-timestamps field))
                             (promoted-columns rmap field)))
                       (sort (into (set (keys (:filterable rmap))) sortable)))
        ix (fn [suffix col]
             [(str "ix_" table "_" suffix)
              (str "CREATE INDEX IF NOT EXISTS ix_" table "_" suffix
                   " ON " table " (" col ")")])]
    {:table table
     :columns (into engine-columns promoted)
     :indexes
     (merge
      (into {} [(ix "state" "state")
                (ix "law" "law_revision")])
      ;; a timestamp index lands only where a declaration NAMES that
      ;; timestamp in :sortable — the non-empty-only rule the create
      ;; and deviations facets already follow, so a kind that never
      ;; asked to sort by the clock hashes exactly as it always did.
      ;; Making them standard would have re-fingerprinted every kind
      ;; in every app and minted a revision apiece at the next boot,
      ;; which is a law change no feature should smuggle. Indexing
      ;; the default created_at ordering for every kind is a real and
      ;; separate question: measure it, then decide.
      (when (contains? sortable :created_at)
        (into {} [(ix "created" "created_at")]))
      (when (contains? sortable :updated_at)
        (into {} [(ix "updated" "updated_at")]))
      {(str "ix_" table "_flip")
       (str "CREATE INDEX IF NOT EXISTS ix_" table "_flip ON " table
            " (next_flip_at) WHERE next_flip_at IS NOT NULL")}
      (gin-indexes rmap table)
      (btree-indexes rmap table promoted)
      ;; declared uniqueness reaches storage (design §24): one UNIQUE
      ;; index per :unique group over the promoted generated columns —
      ;; check-unique already demands every field be promoted, so the
      ;; columns exist by construction
      (into {}
            (map (fn [group]
                   (let [fields (mapv definition-checked-name
                                      (if (keyword? group) [group] group))
                         nm (str "ux_" table "_" (str/join "_" fields))]
                     [nm (str "CREATE UNIQUE INDEX IF NOT EXISTS " nm
                              " ON " table " ("
                              (str/join ", " (map #(str "f_" %) fields))
                              ")")])))
            (:unique rmap)))}))

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
