(ns waymark10.server.store.postgres
  "Postgres storage: per-kind tables with JSONB documents and
  generated columns promoting filterable fields to indexed, typed
  SQL; the waymark10_transitions log (audit + outbox + feed +
  idempotency anchor); pg_notify inside the write transaction so an
  event exists iff its commit does."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)
           (java.sql Timestamp)
           (org.postgresql.util PGobject)))

(set! *warn-on-reflection* true)

(def notify-channel "waymark10_transitions")

;; ── values across the JDBC boundary ─────────────────────────────────

(defn- jsonb ^PGobject [v]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (wire/write-json v))))

(defn- read-jsonb [v]
  (cond
    (instance? PGobject v) (wire/read-json (.getValue ^PGobject v))
    (string? v) (wire/read-json v)
    :else v))

(defn- ->inst [v]
  (if (instance? Timestamp v) (.toInstant ^Timestamp v) v))

(def ^:private jdbc-opts
  {:builder-fn rs/as-unqualified-maps})

;; ── DDL rendering (the projection lives in waymark10.server.store) ──

(def ^:private helper-fns
  ["CREATE OR REPLACE FUNCTION waymark10_date(t text) RETURNS date
      IMMUTABLE STRICT LANGUAGE sql AS 'SELECT t::date'"
   "CREATE OR REPLACE FUNCTION waymark10_ts(t text) RETURNS timestamptz
      IMMUTABLE STRICT LANGUAGE sql AS $$SELECT t::timestamptz$$"])

(defn table-ddl
  "CREATE TABLE IF NOT EXISTS + its indexes, rendered from one
  projection — the same map desired-snapshot canonicalizes; the DDL
  and the drift comparison can never disagree about what a table is."
  [{:keys [table columns constraints indexes]}]
  (cons (str "CREATE TABLE IF NOT EXISTS " table " (\n"
             (str/join ",\n" (map #(str "  " (:ddl %))
                                  columns))
             (str/join (map #(str ",\n  " %) constraints))
             "\n)")
        (map val (sort-by key indexes))))

(defn kind-ddl
  "CREATE TABLE + indexes for one declared kind, from its storage
  projection (store/kind-projection — filterable ∪ sortable promote
  to generated columns; vocab arrays have no single-value promotion
  but DO carry a GIN index since batch F, so membership filters walk
  an index instead of scanning)."
  [rmap]
  (table-ddl (store/kind-projection rmap)))

(def engine-projections
  "The engine's own tables in the same projection shape as a kind's
  (constraints ride only the CREATE — the migrate planner reconciles
  engine tables additively and never touches keys)."
  [;; the transition log: audit + outbox + feed + idempotency anchor
   {:table "waymark10_transitions"
    :columns [{:name "id" :type "bigint" :ddl "id bigserial PRIMARY KEY"}
              {:name "kind" :type "text" :ddl "kind text NOT NULL"}
              {:name "resource_id" :type "text" :ddl "resource_id text NOT NULL"}
              {:name "action" :type "text" :ddl "action text NOT NULL"}
              {:name "from_state" :type "text" :ddl "from_state text"}
              {:name "to_state" :type "text" :ddl "to_state text NOT NULL"}
              {:name "actor" :type "jsonb" :ddl "actor jsonb NOT NULL"}
              {:name "at" :type "timestamptz"
               :ddl "at timestamptz NOT NULL DEFAULT now()"}
              {:name "law_revision" :type "int" :ddl "law_revision int"}
              {:name "input_digest" :type "text" :ddl "input_digest text"}
              {:name "inputs" :type "jsonb" :ddl "inputs jsonb"}
              {:name "acknowledged" :type "jsonb" :ddl "acknowledged jsonb"}
              {:name "correlation_id" :type "text" :ddl "correlation_id text"}
              {:name "idempotency_key" :type "text" :ddl "idempotency_key text"}
              {:name "summary" :type "text" :ddl "summary text"}]
    :indexes {"ix_wm10_t_resource"
              "CREATE INDEX IF NOT EXISTS ix_wm10_t_resource ON waymark10_transitions (kind, resource_id, id)"}}
   {:table "waymark10_idempotency"
    :columns [{:name "key" :type "text" :ddl "key text NOT NULL"}
              {:name "kind" :type "text" :ddl "kind text NOT NULL"}
              {:name "action" :type "text" :ddl "action text NOT NULL"}
              {:name "request_digest" :type "text" :ddl "request_digest text NOT NULL"}
              {:name "status" :type "int" :ddl "status int NOT NULL"}
              ;; text: replay is byte-identical
              {:name "response" :type "text" :ddl "response text NOT NULL"}
              {:name "media_type" :type "text"
               :ddl "media_type text NOT NULL DEFAULT 'application/waymark+json'"}
              {:name "created_at" :type "timestamptz"
               :ddl "created_at timestamptz NOT NULL DEFAULT now()"}]
    :constraints ["PRIMARY KEY (key, kind)"]
    :indexes {}}
   ;; phase 7: the draft rows — audience is "shared" or a principal id
   {:table "waymark10_drafts"
    :columns [{:name "kind" :type "text" :ddl "kind text NOT NULL"}
              {:name "resource_id" :type "text" :ddl "resource_id text NOT NULL"}
              {:name "action" :type "text" :ddl "action text NOT NULL"}
              {:name "audience" :type "text" :ddl "audience text NOT NULL"}
              {:name "values" :type "jsonb"
               :ddl "\"values\" jsonb NOT NULL DEFAULT '{}'::jsonb"}
              {:name "base_version" :type "bigint" :ddl "base_version bigint"}
              {:name "updated_at" :type "timestamptz"
               :ddl "updated_at timestamptz NOT NULL DEFAULT now()"}]
    :constraints ["PRIMARY KEY (kind, resource_id, action, audience)"]
    :indexes {}}
   ;; phase 9b: consumer cursors (the webhook deliverer's at-least-once
   ;; checkpoint) and job leases (claim-or-steal on expiry)
   {:table "waymark10_cursors"
    :columns [{:name "consumer" :type "text" :ddl "consumer text PRIMARY KEY"}
              {:name "position" :type "bigint"
               :ddl "position bigint NOT NULL DEFAULT 0"}
              {:name "updated_at" :type "timestamptz"
               :ddl "updated_at timestamptz NOT NULL DEFAULT now()"}]
    :indexes {}}
   {:table "waymark10_job_leases"
    :columns [{:name "job_id" :type "text" :ddl "job_id text PRIMARY KEY"}
              {:name "holder" :type "text" :ddl "holder text NOT NULL"}
              {:name "expires_at" :type "timestamptz"
               :ddl "expires_at timestamptz NOT NULL"}]
    :indexes {}}])

(def ^:private engine-ddl
  (mapcat table-ddl engine-projections))

;; ── the live snapshot (the migrate planner's other half) ────────────

(def ^:private canonical-type
  "information_schema's long spellings back to the projection's short
  ones, so desired and live compare in one vocabulary."
  {"timestamp with time zone" "timestamptz"
   "integer" "int"
   "character varying" "text"})

(defn table-snapshot
  "The live shape of one table, read from information_schema and
  pg_indexes: {:columns {name {:type … :generated? …}}
  :indexes {name indexdef}}; nil when the table does not exist.
  Postgres normalizes generation expressions, so the snapshot carries
  none — drift compares by name + data type only (see migrate)."
  [st table]
  (with-open [conn (jdbc/get-connection ^HikariDataSource (:ds st))]
    (let [cols (jdbc/execute!
                conn
                [(str "SELECT column_name, data_type, is_generated"
                      " FROM information_schema.columns"
                      " WHERE table_schema = 'public' AND table_name = ?")
                 table]
                jdbc-opts)]
      (when (seq cols)
        {:columns (into {}
                        (map (fn [r]
                               [(:column_name r)
                                {:type (let [t (:data_type r)]
                                         (get canonical-type t t))
                                 :generated? (= "ALWAYS" (:is_generated r))}]))
                        cols)
         :indexes (into {}
                        (map (juxt :indexname :indexdef))
                        (jdbc/execute!
                         conn
                         [(str "SELECT indexname, indexdef FROM pg_indexes"
                               " WHERE schemaname = 'public' AND tablename = ?")
                          table]
                         jdbc-opts))}))))

(defn desired-snapshot
  "The declaration's table in the snapshot shape — the SAME projection
  kind-ddl renders, canonicalized for comparison."
  [rmap]
  (store/projection-snapshot (store/kind-projection rmap)))

(defn distinct-states
  "The state tokens live rows actually occupy — the boot's
  check-state-tokens read and the rename planner's evidence. nil when
  the table does not exist."
  [st table]
  (with-open [conn (jdbc/get-connection ^HikariDataSource (:ds st))]
    (when (pos? (:n (jdbc/execute-one!
                     conn
                     ["SELECT count(*) AS n FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?"
                      table]
                     jdbc-opts)))
      (into (sorted-set)
            (map :state)
            (jdbc/execute! conn [(str "SELECT DISTINCT state FROM "
                                      (store/definition-checked-name table))]
                           jdbc-opts)))))

;; ── row mapping ─────────────────────────────────────────────────────

(defn- row->map [r]
  (when r
    {:id (:id r)
     :state (keyword (:state r))
     :version (:version r)
     :data (read-jsonb (:data r))
     :shape (:shape r)
     :owner (:owner r)
     :law-revision (:law_revision r)
     :next-flip-at (->inst (:next_flip_at r))
     :created-at (->inst (:created_at r))
     :updated-at (->inst (:updated_at r))}))

(defn- transition->map [r]
  (when r
    {:id (:id r)
     :kind (keyword (:kind r))
     :resource-id (:resource_id r)
     :action (keyword (:action r))
     :from-state (some-> (:from_state r) keyword)
     :to-state (keyword (:to_state r))
     :actor (read-jsonb (:actor r))
     :at (->inst (:at r))
     :law-revision (:law_revision r)
     :input-digest (:input_digest r)
     :inputs (read-jsonb (:inputs r))
     :acknowledged (read-jsonb (:acknowledged r))
     :correlation-id (:correlation_id r)
     :idempotency-key (:idempotency_key r)
     :summary (:summary r)}))

;; ── the condition grammar (phase 6; collections widen it, phase 7) ──

(def ^:private safe-casts #{"date" "boolean" "bigint" "numeric" "text"
                            "timestamptz"})

(def ^:private cond-ops {:= "=" :< "<" :<= "<=" :>= ">=" :> ">"})

(defn- cond-sql
  "One cond → [sql-fragment params]. Identifiers come from checked
  declarations; casts from a closed set — anything else is refused
  loudly, never spliced. Phase 7 adds :op :in-any (JSONB array
  membership, any-of — spelled through jsonb_exists_any because ?| is
  a JDBC placeholder collision) and the timestamptz cast for _after
  filters."
  [{:keys [target field cast op value values]}]
  (if (= :in-any op)
    ;; the ?| operator (JDBC-escaped ??|), not jsonb_exists_any: the
    ;; planner matches INDEXES through operators only, so the function
    ;; spelling could never walk the vocab GIN index (batch F)
    (let [f (store/definition-checked-name field)]
      [(str "data->'" f "' ??| ARRAY["
            (str/join ", " (repeat (count values) "?")) "]")
       (vec values)])
    (let [cast (or cast "text")
          _ (when-not (contains? safe-casts cast)
              (throw (ex-info (str "cond cast " (pr-str cast)
                                   " is not a known SQL type") {:cast cast})))
          lval (case target
                 :state "state"
                 :id "id"
                 (let [f (store/definition-checked-name field)]
                   (case cast
                     "date" (str "waymark10_date(data->>'" f "')")
                     "timestamptz" (str "waymark10_ts(data->>'" f "')")
                     "text" (str "data->>'" f "'")
                     (str "(data->>'" f "')::" cast))))
          rval (if (or (contains? #{:state :id} target) (= "text" cast))
                 "?"
                 (case cast
                   "date" "waymark10_date(?)"
                   "timestamptz" "waymark10_ts(?)"
                   (str "(?)::" cast)))]
      (if (= :in op)
        [(str lval " IN (" (str/join ", " (repeat (count values) rval)) ")")
         (vec values)]
        [(str lval " " (or (get cond-ops op)
                           (throw (ex-info (str "unknown cond op " op) {:op op})))
              " " rval)
         [value]]))))

;; ── the storage ─────────────────────────────────────────────────────

(defrecord PostgresStorage [^HikariDataSource ds tables]
  store/Storage
  (with-tx* [_ f]
    (jdbc/with-transaction [tx ds]
      (f tx)))

  (ensure-kind! [_ rmap]
    (with-open [conn (jdbc/get-connection ds)]
      (doseq [stmt (concat helper-fns engine-ddl (kind-ddl rmap))]
        (jdbc/execute! conn [stmt])))
    (swap! tables assoc (:kind rmap)
           (store/definition-checked-name (:plural rmap)))
    nil)

  (load-row [_ tx kind id opts]
    (let [table (get @tables kind)
          sql (str "SELECT * FROM " table " WHERE id = ?"
                   (when (:for-update opts) " FOR UPDATE"))]
      (row->map (jdbc/execute-one! tx [sql id] jdbc-opts))))

  (insert-row! [_ tx kind row]
    (let [table (get @tables kind)]
      (jdbc/execute-one!
       tx
       [(str "INSERT INTO " table
             " (id, state, version, data, shape, owner, law_revision, next_flip_at)"
             " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        (:id row) (name (:state row)) (:version row 1)
        (jsonb (:data row)) (:shape row 1) (:owner row)
        (:law-revision row)
        (some-> ^java.time.Instant (:next-flip-at row) Timestamp/from)])
      row))

  (save-row! [_ tx kind row expected-version]
    (let [table (get @tables kind)
          res (jdbc/execute-one!
               tx
               [(str "UPDATE " table
                     " SET state = ?, version = ?, data = ?, shape = ?,"
                     " owner = ?, law_revision = ?, next_flip_at = ?,"
                     " updated_at = now()"
                     " WHERE id = ? AND version = ?"
                     " RETURNING updated_at")
                (name (:state row)) (:version row) (jsonb (:data row))
                (:shape row 1) (:owner row) (:law-revision row)
                (some-> ^java.time.Instant (:next-flip-at row) Timestamp/from)
                (:id row) expected-version]
               jdbc-opts)]
      (when (nil? res)
        (throw (store/version-conflict kind (:id row) expected-version)))
      ;; the write's own stamp, so post-invoke envelopes never carry
      ;; the pre-write time
      (assoc row :updated-at (->inst (:updated_at res)))))

  (query-rows [_ tx kind where opts]
    (let [table (get @tables kind)
          clauses (map (fn [[f _]]
                         (if (= f :state)
                           "state = ?"
                           (str "data->>'" (store/definition-checked-name f) "' = ?")))
                       where)
          params (map (fn [[f v]]
                        (if (= f :state) (name v) (str v)))
                      where)
          sql (str "SELECT * FROM " table
                   (when (seq clauses) (str " WHERE " (str/join " AND " clauses)))
                   " ORDER BY " (if-some [o (:order-by opts)]
                                  (store/definition-checked-name o)
                                  "created_at")
                   " LIMIT " (long (:limit opts 100)))]
      (mapv row->map (jdbc/execute! tx (into [sql] params) jdbc-opts))))

  (append-transition! [_ tx record]
    (let [res (jdbc/execute-one!
               tx
               [(str "INSERT INTO waymark10_transitions"
                     " (kind, resource_id, action, from_state, to_state, actor,"
                     "  law_revision, input_digest, inputs, acknowledged,"
                     "  correlation_id, idempotency_key, summary)"
                     " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                     " RETURNING id, at")
                (name (:kind record)) (:resource-id record)
                (name (:action record))
                (some-> (:from-state record) name)
                (name (:to-state record))
                (jsonb (:actor record))
                (:law-revision record)
                (:input-digest record)
                (some-> (:inputs record) jsonb)
                (some-> (:acknowledged record) not-empty jsonb)
                (:correlation-id record)
                (:idempotency-key record)
                (:summary record)]
               jdbc-opts)
          id (:id res)]
      ;; the outbox IS the log: notify rides the write transaction,
      ;; so subscribers learn of exactly the transitions that committed
      (jdbc/execute-one! tx ["SELECT pg_notify(?, ?)" notify-channel (str id)])
      (assoc record :id id :at (->inst (:at res)))))

  (transitions [_ tx where opts]
    (let [clauses (cond-> []
                    (:kind where) (conj ["kind = ?" (name (:kind where))])
                    (:resource-id where) (conj ["resource_id = ?" (:resource-id where)])
                    (:since where) (conj ["id > ?" (:since where)]))
          sql (str "SELECT * FROM waymark10_transitions"
                   (when (seq clauses)
                     (str " WHERE " (str/join " AND " (map first clauses))))
                   " ORDER BY id" (when (:newest-first opts) " DESC")
                   " LIMIT " (long (:limit opts 500)))]
      (mapv transition->map
            (jdbc/execute! tx (into [sql] (map second clauses)) jdbc-opts))))

  (idempotency-lookup [_ tx key kind]
    (when-some [r (jdbc/execute-one!
                   tx ["SELECT * FROM waymark10_idempotency WHERE key = ? AND kind = ?"
                       key (name kind)]
                   jdbc-opts)]
      {:status (:status r)
       :response (:response r)
       :media-type (:media_type r)
       :request-digest (:request_digest r)
       :action (keyword (:action r))}))

  (idempotency-store! [_ tx key kind action digest status response media-type]
    (jdbc/execute-one!
     tx ["INSERT INTO waymark10_idempotency (key, kind, action, request_digest, status, response, media_type) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (key, kind) DO NOTHING"
         key (name kind) (name action) digest status response media-type])
    nil)

  (law-count [_ tx kind revision]
    (let [table (get @tables kind)]
      (:n (jdbc/execute-one!
           tx [(str "SELECT count(*) AS n FROM " table
                    " WHERE law_revision = ?")
               revision]
           jdbc-opts))))

  ;; ── phase 6: the maintainer's reads and the maintenance write ──────

  (count-matching [_ tx kind conds]
    (let [table (get @tables kind)
          parts (map cond-sql conds)
          sql (str "SELECT count(*) AS n FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts)))))]
      (:n (jdbc/execute-one! tx (into [sql] (mapcat second parts)) jdbc-opts))))

  (sum-matching [_ tx kind of conds]
    (let [table (get @tables kind)
          fname (store/definition-checked-name of)
          parts (map cond-sql conds)
          sql (str "SELECT COALESCE(SUM((data->>'" fname "')::numeric), 0) AS s"
                   " FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts)))))]
      (:s (jdbc/execute-one! tx (into [sql] (mapcat second parts)) jdbc-opts))))

  (ids-matching [_ tx kind conds limit]
    (let [table (get @tables kind)
          parts (map cond-sql conds)
          sql (str "SELECT id FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts))))
                   " ORDER BY id LIMIT " (long limit))]
      (mapv :id (jdbc/execute! tx (into [sql] (mapcat second parts)) jdbc-opts))))

  (update-data! [_ tx kind id data next-flip-at]
    (let [table (get @tables kind)]
      (jdbc/execute-one!
       tx
       [(str "UPDATE " table
             " SET data = ?, next_flip_at = ?, updated_at = now()"
             " WHERE id = ?")
        (jsonb data)
        (some-> ^java.time.Instant next-flip-at Timestamp/from)
        id])
      nil))

  (due-flips [_ tx kind now limit]
    (let [table (get @tables kind)]
      (mapv row->map
            (jdbc/execute!
             tx
             [(str "SELECT * FROM " table
                   " WHERE next_flip_at IS NOT NULL AND next_flip_at <= ?"
                   " ORDER BY next_flip_at LIMIT " (long limit)
                   " FOR UPDATE")
              (Timestamp/from ^java.time.Instant now)]
             jdbc-opts))))

  ;; ── phase 7: the collection surface and the draft rows ─────────────

  (search-rows [_ tx kind conds {:keys [order-by desc limit offset]}]
    (let [table (get @tables kind)
          parts (map cond-sql conds)
          order (case order-by
                  nil "created_at"
                  :state "state"
                  (str "f_" (store/definition-checked-name order-by)))
          sql (str "SELECT * FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts))))
                   " ORDER BY " order (when desc " DESC") ", id"
                   " LIMIT " (long (or limit 100))
                   " OFFSET " (long (or offset 0)))]
      (mapv row->map (jdbc/execute! tx (into [sql] (mapcat second parts))
                                    jdbc-opts))))

  (facet-counts [_ tx kind field conds array?]
    (let [table (get @tables kind)
          parts (map cond-sql conds)
          expr (cond
                 (= :state field) "state"
                 array? (str "jsonb_array_elements_text(data->'"
                             (store/definition-checked-name field) "')")
                 :else (str "data->>'"
                            (store/definition-checked-name field) "'"))
          sql (str "SELECT " expr " AS v, count(*) AS n FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts))))
                   " GROUP BY 1 ORDER BY 1")]
      (into (sorted-map)
            (keep (fn [r] (when (some? (:v r)) [(:v r) (:n r)])))
            (jdbc/execute! tx (into [sql] (mapcat second parts)) jdbc-opts))))

  (load-draft [_ tx kind id action audience]
    (when-some [r (jdbc/execute-one!
                   tx [(str "SELECT \"values\", base_version, updated_at"
                            " FROM waymark10_drafts WHERE kind = ? AND"
                            " resource_id = ? AND action = ? AND audience = ?")
                       (name kind) id (name action) audience]
                   jdbc-opts)]
      {:values (read-jsonb (:values r))
       :base-version (:base_version r)
       :updated-at (->inst (:updated_at r))}))

  (save-draft! [_ tx kind id action audience values base-version]
    (jdbc/execute-one!
     tx [(str "INSERT INTO waymark10_drafts"
              " (kind, resource_id, action, audience, \"values\", base_version)"
              " VALUES (?, ?, ?, ?, ?, ?)"
              " ON CONFLICT (kind, resource_id, action, audience) DO UPDATE"
              " SET \"values\" = EXCLUDED.\"values\","
              " base_version = EXCLUDED.base_version, updated_at = now()")
         (name kind) id (name action) audience (jsonb values) base-version])
    nil)

  (delete-draft! [_ tx kind id action audience]
    (jdbc/execute-one!
     tx [(str "DELETE FROM waymark10_drafts WHERE kind = ? AND"
              " resource_id = ? AND action = ? AND audience = ?")
         (name kind) id (name action) audience])
    nil)

  ;; ── phase 9b: consumer cursors and job leases ──────────────────────

  (cursor-get [_ tx consumer]
    (:position (jdbc/execute-one!
                tx ["SELECT position FROM waymark10_cursors WHERE consumer = ?"
                    consumer]
                jdbc-opts)))

  (cursor-set! [_ tx consumer position]
    (jdbc/execute-one!
     tx [(str "INSERT INTO waymark10_cursors (consumer, position)"
              " VALUES (?, ?)"
              " ON CONFLICT (consumer) DO UPDATE"
              " SET position = EXCLUDED.position, updated_at = now()")
         consumer (long position)])
    nil)

  (claim-job-lease! [_ tx job-id holder ttl-seconds]
    (let [res (jdbc/execute-one!
               tx [(str "INSERT INTO waymark10_job_leases"
                        " (job_id, holder, expires_at)"
                        " VALUES (?, ?, now() + make_interval(secs => ?))"
                        " ON CONFLICT (job_id) DO UPDATE"
                        " SET holder = EXCLUDED.holder,"
                        " expires_at = EXCLUDED.expires_at"
                        " WHERE waymark10_job_leases.expires_at <= now()"
                        " OR waymark10_job_leases.holder = EXCLUDED.holder")
                   job-id holder (double ttl-seconds)])]
      (= 1 (:next.jdbc/update-count res))))

  (release-job-lease! [_ tx job-id holder]
    (jdbc/execute-one!
     tx ["DELETE FROM waymark10_job_leases WHERE job_id = ? AND holder = ?"
         job-id holder])
    nil)

  (job-lease [_ tx job-id]
    (when-some [r (jdbc/execute-one!
                   tx ["SELECT holder, expires_at FROM waymark10_job_leases WHERE job_id = ?"
                       job-id]
                   jdbc-opts)]
      {:holder (:holder r)
       :expires-at (->inst (:expires_at r))}))

  (restamp-law! [_ tx kind where to-revision]
    (let [table (get @tables kind)
          clauses (map (fn [[f _]]
                         (case f
                           :state "state = ?"
                           :law-revision "law_revision = ?"
                           (str "data->>'" (store/definition-checked-name f)
                                "' = ?")))
                       where)
          params (map (fn [[f v]]
                        (case f
                          :state (name v)
                          :law-revision v
                          (str v)))
                      where)
          res (jdbc/execute-one!
               tx (into [(str "UPDATE " table
                              " SET law_revision = ?, updated_at = now()"
                              (when (seq clauses)
                                (str " WHERE " (str/join " AND " clauses))))
                         to-revision]
                        params))]
      (:next.jdbc/update-count res))))

(defn listen-connection
  "A dedicated raw JDBC connection LISTENing the outbox channel —
  deliberately NOT from the Hikari pool: getNotifications parks the
  connection for the dispatcher's lifetime. The caller owns closing
  it (waymark10.server.events/stop!)."
  ^java.sql.Connection [^PostgresStorage st]
  (let [url (.getJdbcUrl ^HikariDataSource (:ds st))
        conn (java.sql.DriverManager/getConnection url)]
    (with-open [stmt (.createStatement conn)]
      (.execute stmt (str "LISTEN " notify-channel)))
    conn))

(defn storage
  "A pooled Postgres storage. jdbc-url e.g.
  jdbc:postgresql://localhost:5433/waymark10_test?user=ckopsa"
  [jdbc-url]
  (let [cfg (doto (HikariConfig.)
              (.setJdbcUrl jdbc-url)
              (.setMaximumPoolSize 8)
              (.setPoolName "waymark10"))]
    (->PostgresStorage (HikariDataSource. cfg) (atom {}))))

(defn close! [^PostgresStorage st]
  (.close ^HikariDataSource (:ds st)))
