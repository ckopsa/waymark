(ns waymark10.server.store.postgres
  "Postgres storage: per-kind tables with JSONB documents and
  generated columns promoting filterable fields to indexed, typed
  SQL; the waymark10_transitions log (audit + outbox + feed +
  idempotency anchor); pg_notify inside the write transaction so an
  event exists iff its commit does."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.schema :as schema]
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

;; ── DDL projection ──────────────────────────────────────────────────

(def ^:private helper-fns
  ["CREATE OR REPLACE FUNCTION waymark10_date(t text) RETURNS date
      IMMUTABLE STRICT LANGUAGE sql AS 'SELECT t::date'"
   "CREATE OR REPLACE FUNCTION waymark10_ts(t text) RETURNS timestamptz
      IMMUTABLE STRICT LANGUAGE sql AS $$SELECT t::timestamptz$$"])

(defn- sortable-fields [rmap]
  (get-in rmap [:sortable :fields]))

(defn- generated-column-type
  "SQL type for a promoted filterable field, from its schema form;
  nil when the field has no single-value promotion (vocab arrays get
  GIN in phase 7)."
  [field-schema]
  (let [head (if (vector? field-schema) (first field-schema) field-schema)]
    (case head
      :waymark/date "date"
      :boolean "boolean"
      :int "bigint"
      (:double :decimal) "numeric"
      (:string :waymark/ref :waymark/vocab :enum) "text"
      nil)))

(defn- generated-column [field field-schema]
  (let [fname (store/definition-checked-name field)
        sql-type (generated-column-type field-schema)]
    (when sql-type
      (str "f_" fname " " sql-type
           " GENERATED ALWAYS AS ("
           (case sql-type
             "date" (str "waymark10_date(data->>'" fname "')")
             "boolean" (str "(data->>'" fname "')::boolean")
             "bigint" (str "(data->>'" fname "')::bigint")
             "numeric" (str "(data->>'" fname "')::numeric")
             (str "data->>'" fname "'"))
           ") STORED"))))

(defn kind-ddl
  "CREATE TABLE + indexes for one declared kind. Promoted columns are
  the filterable ∪ sortable fields (phase 7 widened filterable-only:
  sort orders by the generated column, so sortable fields promote
  too); vocab arrays have no single-value promotion and no GIN index
  yet (named punt) — containment filters scan."
  [rmap]
  (let [table (store/definition-checked-name (:plural rmap))
        promoted (keep (fn [field]
                         (when-not (= field :state)
                           (generated-column
                            field (schema/field-schema (:schema rmap) field))))
                       (sort (into (set (keys (:filterable rmap)))
                                   (sortable-fields rmap))))]
    (concat
     [(str "CREATE TABLE IF NOT EXISTS " table " (\n"
           (str/join ",\n"
                     (concat
                      ["  id text PRIMARY KEY"
                       "  state text NOT NULL"
                       "  version bigint NOT NULL DEFAULT 1"
                       "  data jsonb NOT NULL"
                       "  shape int NOT NULL DEFAULT 1"
                       "  owner text"
                       "  law_revision int"
                       "  next_flip_at timestamptz"
                       "  created_at timestamptz NOT NULL DEFAULT now()"
                       "  updated_at timestamptz NOT NULL DEFAULT now()"]
                      (map #(str "  " %) promoted)))
           "\n)")
      (str "CREATE INDEX IF NOT EXISTS ix_" table "_state ON " table " (state)")
      (str "CREATE INDEX IF NOT EXISTS ix_" table "_law ON " table " (law_revision)")
      (str "CREATE INDEX IF NOT EXISTS ix_" table "_flip ON " table
           " (next_flip_at) WHERE next_flip_at IS NOT NULL")])))

(def ^:private engine-ddl
  [(str "CREATE TABLE IF NOT EXISTS waymark10_transitions (\n"
        "  id bigserial PRIMARY KEY,\n"
        "  kind text NOT NULL,\n"
        "  resource_id text NOT NULL,\n"
        "  action text NOT NULL,\n"
        "  from_state text,\n"
        "  to_state text NOT NULL,\n"
        "  actor jsonb NOT NULL,\n"
        "  at timestamptz NOT NULL DEFAULT now(),\n"
        "  law_revision int,\n"
        "  input_digest text,\n"
        "  inputs jsonb,\n"
        "  acknowledged jsonb,\n"
        "  correlation_id text,\n"
        "  idempotency_key text,\n"
        "  summary text)")
   "CREATE INDEX IF NOT EXISTS ix_wm10_t_resource ON waymark10_transitions (kind, resource_id, id)"
   (str "CREATE TABLE IF NOT EXISTS waymark10_idempotency (\n"
        "  key text NOT NULL,\n"
        "  kind text NOT NULL,\n"
        "  action text NOT NULL,\n"
        "  request_digest text NOT NULL,\n"
        "  status int NOT NULL,\n"
        "  response text NOT NULL,\n"          ; text: replay is byte-identical
        "  media_type text NOT NULL DEFAULT 'application/waymark+json',\n"
        "  created_at timestamptz NOT NULL DEFAULT now(),\n"
        "  PRIMARY KEY (key, kind))")
   ;; phase 7: the draft rows — audience is "shared" or a principal id
   (str "CREATE TABLE IF NOT EXISTS waymark10_drafts (\n"
        "  kind text NOT NULL,\n"
        "  resource_id text NOT NULL,\n"
        "  action text NOT NULL,\n"
        "  audience text NOT NULL,\n"
        "  \"values\" jsonb NOT NULL DEFAULT '{}'::jsonb,\n"
        "  base_version bigint,\n"
        "  updated_at timestamptz NOT NULL DEFAULT now(),\n"
        "  PRIMARY KEY (kind, resource_id, action, audience))")])

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

(def ^:private jdbc-opts
  {:builder-fn rs/as-unqualified-maps})

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
    (let [f (store/definition-checked-name field)]
      [(str "jsonb_exists_any(data->'" f "', ARRAY["
            (str/join ", " (repeat (count values) "?")) "])")
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
