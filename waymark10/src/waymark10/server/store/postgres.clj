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
  "CREATE TABLE + indexes for one declared kind."
  [rmap]
  (let [table (store/definition-checked-name (:plural rmap))
        promoted (keep (fn [[field _ops]]
                         (when-not (= field :state)
                           (generated-column
                            field (schema/field-schema (:schema rmap) field))))
                       (:filterable rmap))]
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
        "  PRIMARY KEY (key, kind))")])

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
    nil))

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
