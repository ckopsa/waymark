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
           (java.nio.charset StandardCharsets)
           (java.sql Connection DriverManager SQLException Timestamp)
           (java.util.zip CRC32)
           (org.postgresql PGConnection)
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
              ;; the decision record (spec-decision-record): the
              ;; EVIDENCE the guards read, written only by a kind that
              ;; declares :retain {:judgment true}. Which guards judged
              ;; is derived from law_revision and needs no column;
              ;; what they read is gone at commit and needs this one
              {:name "judgment" :type "jsonb" :ddl "judgment jsonb"}
              ;; time travel tier 3 (spec-time-travel): the document as
              ;; the write left it, secret fields subtracted, written
              ;; only by a kind that declares :retain {:data true}
              {:name "after" :type "jsonb" :ddl "after jsonb"}
              {:name "correlation_id" :type "text" :ddl "correlation_id text"}
              {:name "idempotency_key" :type "text" :ddl "idempotency_key text"}
              {:name "summary" :type "text" :ddl "summary text"}]
    :indexes {"ix_wm10_t_resource"
              "CREATE INDEX IF NOT EXISTS ix_wm10_t_resource ON waymark10_transitions (kind, resource_id, id)"
              ;; the time axis (seasons, waymark-tti.2): transition-stats
              ;; walks `at >= ?`, which the (kind, resource_id, id)
              ;; index cannot serve
              "ix_wm10_t_at"
              "CREATE INDEX IF NOT EXISTS ix_wm10_t_at ON waymark10_transitions (at)"}}
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

(def prerequisites
  "Everything a kind's DDL presumes exists: the helper functions its
  generated columns call and the engine's own tables. Idempotent
  (CREATE OR REPLACE / IF NOT EXISTS) — ensure-kind! runs these at
  boot, and migrate/apply! runs them before a plan, so a VIRGIN
  production database migrates from the CLI alone (found by the
  mealplan10 cutover: a fresh db has no waymark10_date and the first
  promoted date column refused)."
  (vec (concat helper-fns engine-ddl)))

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
     ;; the decision record. A column absent HERE is invisible to
     ;; every reader, whatever the INSERT wrote — the one line in this
     ;; file that decides whether a column exists as far as the engine
     ;; is concerned
     :judgment (read-jsonb (:judgment r))
     :after (read-jsonb (:after r))
     :correlation-id (:correlation_id r)
     :idempotency-key (:idempotency_key r)
     :summary (:summary r)}))

;; ── the condition grammar (phase 6; collections widen it, phase 7) ──

(def ^:private safe-casts #{"date" "boolean" "bigint" "numeric" "text"
                            "timestamptz"})

(def ^:private cond-ops {:= "=" :not= "<>" :< "<" :<= "<=" :>= ">=" :> ">"})

(defn- escape-like
  "A literal value made safe inside a LIKE pattern — the wildcards
  and the escape character itself lose their powers."
  ^String [^String s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "%" "\\%")
      (str/replace "_" "\\_")))

(defn- cond-sql
  "One cond → [sql-fragment params]. Identifiers come from checked
  declarations; casts from a closed set — anything else is refused
  loudly, never spliced. Phase 7 adds :op :in-any (JSONB array
  membership, any-of — spelled through jsonb_exists_any because ?| is
  a JDBC placeholder collision) and the timestamptz cast for _after
  filters; the ne/before/set/contains batch adds :not=/:not-in (NULL
  fails both, SQL semantics), :set? (IS [NOT] NULL over the extracted
  text), and :contains (ILIKE over the text, the value's wildcards
  escaped)."
  [{:keys [target field cast op value values absent?]}]
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
      (let [[sql params]
            (case op
              :in [(str lval " IN ("
                        (str/join ", " (repeat (count values) rval)) ")")
                   (vec values)]
              :not-in [(str lval " NOT IN ("
                            (str/join ", " (repeat (count values) rval)) ")")
                       (vec values)]
              :set? [(str lval (if value " IS NOT NULL" " IS NULL")) []]
              :contains [(str lval " ILIKE ? ESCAPE '\\'")
                         [(str "%" (escape-like value) "%")]]
              [(str lval " " (or (get cond-ops op)
                                 (throw (ex-info (str "unknown cond op " op)
                                                 {:op op})))
                    " " rval)
               [value]])]
        ;; :absent? (the kind's :absent-as): the absent row belongs in
        ;; this answer — the coalesce, spelled as OR IS NULL so the
        ;; comparison itself stays the one an index can serve
        (if absent?
          [(str "(" sql " OR " lval " IS NULL)") params]
          [sql params])))))

;; ── elected singletons (pg_advisory_lock) ───────────────────────────
;; Lived in server/coherence.clj until waymark-db9.4. It moved here
;; because election is a STORAGE capability, not a module's: a
;; lifecycle hook declares `:elected <role>` and the engine asks the
;; storage to elect, so the in-memory twin degrades the same hook to a
;; plain start instead of the engine learning which backend it has.
;; coherence keeps start-role!/stop-role! as the named primitive, now
;; delegating here.

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 postgres: " parts))))

(def lock-namespace
  "The high 32 bits of every waymark10 advisory-lock key: the ASCII
  bytes \"WM10\". Documented so no other tenant of the database claims
  the word by accident."
  0x574D3130)

(defn role-lock-key
  "role name → the pg advisory-lock bigint: (\"WM10\" << 32) |
  crc32(utf-8 name). Deterministic across JVMs, collision-free for the
  four names in use (webhooks-deliverer, clock-sweeper,
  jobs-orphan-sweeper, attachments-purge), and disjoint from any other
  application's advisory keys unless it also claims the WM10 word.

  The NAME is the keyspace, which is why waymark10.modules spells
  `:elected` as a role keyword rather than a bare true: a hook
  renamed is a lock renamed, and two versions of this artifact in a
  rolling deploy would both think themselves the only holder."
  ^long [role-name]
  (let [crc (doto (CRC32.)
              (.update (.getBytes (name role-name) StandardCharsets/UTF_8)))]
    (bit-or (bit-shift-left (long lock-namespace) 32) (.getValue crc))))

;; ── the log's commit order ──────────────────────────────────────────
;; A transition's id comes from the sequence inside its writer's own
;; transaction, so two overlapping writers can COMMIT out of id order,
;; and a reader that walks the log by id (`id > cursor`) would step
;; over the lower id for good. A transaction therefore, before its
;; first append, reads the sequence's last value — a FLOOR: every id it
;; allocates is above it — and holds a SHARED advisory lock whose key
;; spells that floor until it ends. Nothing takes that lock
;; exclusively, so it blocks no one: it is a note in pg_locks that a
;; transaction in flight may hold any id above it.
;;
;; A reader asking for the settled log reads the sequence (B), then the
;; notes (M, the lowest floor held), then the rows with id <= min(B, M).
;; An id at or under B was allocated before the notes were read, so its
;; writer has ended — and the statement's snapshot (READ COMMITTED,
;; taken after) sees its row — or its note is among those read and its
;; id is above M. The reader takes no lock, and no append waits on it
;; (ticket 3a23b699; the exclusive lock this replaces queued every
;; append behind a waiting reader).
;;
;; A long transaction that appended holds the watermark at its floor
;; until it ends: the reader gets the rows under it and no row above.
;; A long transaction that never appended leaves no note and holds
;; nothing back. The sequence must keep CACHE 1 (bigserial's default):
;; a cached id is under the last value another session reads.

(def ^:private log-floor-sql
  "The transition sequence's last value, 0 before the first id."
  (str "coalesce(pg_sequence_last_value("
       "pg_get_serial_sequence('waymark10_transitions', 'id')::regclass), 0)"))

(def ^:private log-floor-setting
  "Transaction-local: set once this transaction holds its floor, so a
  transaction of many appends holds one lock and not one for each."
  "waymark10.log_floor")

;; What the watermark costs a writer, from `order-lock-cost-to-writers`
;; (3 writers for 1.5 s behind a long writer, unsettled readers; one
;; runner for both lines of a pair, and runners differ by a factor of
;; two, so read a pair and not a number alone):
;;   floor in statements of its own  10235 appends without it, 6891 with
;;                                   (p50 0.4 ms against 0.6 ms)
;;   floor in the append's INSERT     6845 appends without it, 6866 with
;;                                   (p50 0.6 ms both, p99 1.7 against 1.6)
;; And the notify, on one runner (run 37479201096, no long writer):
;;   notify in a statement of its own  4271 and 4371 appends, p50 1.0 ms
;;   notify in the append's INSERT     5597 and 5532 appends, p50 0.8 ms
;; With the fold, settled readers behind a long writer: 6961 appends,
;; p50 0.6 ms, p99 1.0 ms. The fall from 8678 to 2917 between two CI
;; runs was mostly the runners; the floor's own statements took a third.
(def ^:private settle-wait-ms
  "How long a settled read with no row to give waits for the writers
  in flight under it, before it answers nil. It waits by asking again
  every `settle-poll-ms` and holds no lock, so no append waits on it
  (`order-lock-cost-to-writers` prints the four cases). An append
  commits in about 1 ms (p99 under 5 ms), so 50 outwaits the ordinary
  writer."
  50)

(def ^:private settle-poll-ms 10)

(def ^:private hold-log-floor-sql
  "A transaction's first append: note in pg_locks the floor its ids
  will be above (see the log's commit order), and give no row once the
  transaction holds one. The lock is shared and never asked for
  exclusively, so this waits on nothing."
  ;; the two-key form, so the floor is read back from pg_locks.objid;
  ;; its low 32 bits, which log-watermark reads against the sequence
  (str "SELECT pg_advisory_xact_lock_shared(" (int lock-namespace)
       ", (" log-floor-sql ")::bit(32)::int4),"
       " set_config('" log-floor-setting "', 'held', true)"
       " WHERE coalesce(current_setting('" log-floor-setting "', true), '') = ''"))

(defn- append-sql
  "The append's INSERT. With floor? the same statement holds the log's
  floor first, so an append is one round trip: the INSERT reads its one
  row from a count over the floor's, and the id's default is evaluated
  for that row, after the lock is held. The INSERT is itself a CTE, and
  the statement's own SELECT notifies the channel (the last parameter)
  of the id it returned."
  [floor?]
  (str "WITH " (when floor? (str "floor AS (" hold-log-floor-sql "), "))
       "appended AS ("
       "INSERT INTO waymark10_transitions"
       " (kind, resource_id, action, from_state, to_state, actor,"
       "  law_revision, input_digest, inputs, acknowledged,"
       "  judgment, after, correlation_id, idempotency_key, summary)"
       " SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
       (when floor? " FROM (SELECT count(*) FROM floor) held")
       " RETURNING id, at)"
       " SELECT id, at, pg_notify(?, id::text) AS notified FROM appended"))

(def ^:private append-with-floor-sql (append-sql true))

(defn- log-watermark
  "→ {:bound id :cut? bool}: every id at or under :bound has committed
  or is gone for good; :cut? says a transaction in flight may hold an
  id between :bound and the newest allocated. The three reads are in
  this order on purpose (see the log's commit order)."
  [tx]
  (let [last-id #(long (-> (jdbc/execute-one! tx [(str "SELECT " log-floor-sql " AS v")])
                           vals first))
        newest (last-id)
        held (mapv #(long (first (vals %)))
                   (jdbc/execute!
                    tx [(str "SELECT objid::bigint AS v FROM pg_locks"
                             " WHERE locktype = 'advisory' AND granted"
                             " AND objsubid = 2 AND classid::bigint = ?"
                             " AND database = (SELECT oid FROM pg_database"
                             "  WHERE datname = current_database())")
                        (long lock-namespace)]))
        ;; a floor noted after `newest` was read may be above it: the
        ;; 32 bits are read against a value no held floor is above
        at (if (seq held) (last-id) newest)
        floors (map #(- at (bit-and (- at %) 0xFFFFFFFF)) held)
        bound (reduce min newest floors)]
    {:bound bound :cut? (< bound newest)}))

(defn- settled-read
  "The settled log through `read`, a fn of the highest id it may
  answer. The rows under the watermark are the answer when there are
  any, in either order: a newest-first read then answers the newest id
  UNDER the writers in flight, which is a sound cursor — every id at
  or under it has committed, and the ids above it are a later read's
  (ticket dea2b35e; answering nil here starved a seed under writers
  that overlap without a gap). With none to give while a transaction
  in flight holds the watermark down it asks again for
  `settle-wait-ms` and then answers nil. It holds no lock while it
  waits."
  [tx read]
  (let [deadline (+ (System/nanoTime) (* 1000000 (long settle-wait-ms)))]
    (loop []
      (let [{:keys [bound cut?]} (log-watermark tx)
            rows (read bound)]
        (cond
          (or (seq rows) (not cut?)) rows
          (< (System/nanoTime) deadline) (do (Thread/sleep (long settle-poll-ms))
                                             (recur))
          :else nil)))))

(defn- lock-connection
  "A dedicated raw JDBC connection for one role's lock — deliberately
  NOT from the Hikari pool (the listen-connection discipline): the
  advisory lock is session-scoped, and the pool recycling the session
  would drop it silently."
  ^Connection [^HikariDataSource ds]
  (DriverManager/getConnection (.getJdbcUrl ds)))

(defn- try-lock? [conn ^long key]
  (boolean (:locked (jdbc/execute-one!
                     conn ["SELECT pg_try_advisory_lock(?) AS locked" key]
                     jdbc-opts))))

(defn- elect!
  "The election loop: acquire pg_try_advisory_lock(role-lock-key
  role-name) on a dedicated connection, retrying every :retry-ms; the
  holder calls (start-fn) and holds until released or the session dies
  (checked every retry interval), then (stop-fn handle) runs and the
  session closes — releasing the lock, so the peer's next try takes
  over."
  [^HikariDataSource ds role-name {:keys [retry-ms start-fn stop-fn]
                                   :or {retry-ms 5000}}]
  (let [key (role-lock-key role-name)
        running (atom true)
        held? (atom false)
        starts (atom 0)
        t (Thread.
           ^Runnable
           (fn []
             (while @running
               (let [conn (try (lock-connection ds)
                               (catch Exception e
                                 (when @running
                                   (warn! "role " (name role-name)
                                          ": no lock connection ("
                                          (ex-message e) "); retrying"))
                                 nil))]
                 (if (nil? conn)
                   (try (Thread/sleep (long retry-ms))
                        (catch InterruptedException _ nil))
                   (try
                     ;; contend
                     (loop []
                       (when (and @running (not (try-lock? conn key)))
                         (Thread/sleep (long retry-ms))
                         (recur)))
                     ;; hold
                     (when @running
                       (swap! starts inc)
                       (reset! held? true)
                       (let [handle (start-fn)]
                         (try
                           (loop []
                             (when (and @running (.isValid ^Connection conn 2))
                               (Thread/sleep (long retry-ms))
                               (recur)))
                           (finally
                             (reset! held? false)
                             (try (when stop-fn (stop-fn handle))
                                  (catch Exception e
                                    (warn! "role " (name role-name)
                                           " stop-fn: " (ex-message e))))))))
                     (catch InterruptedException _ nil)
                     (catch Exception e
                       (when @running
                         (warn! "role " (name role-name) " loop: "
                                (ex-message e))
                         (try (Thread/sleep (long retry-ms))
                              (catch InterruptedException _ nil))))
                     (finally
                       ;; closing the session releases the lock
                       (try (.close ^Connection conn)
                            (catch Exception _ nil))))))))
           (str "waymark10-role-" (name role-name)))]
    (doto ^Thread t (.setDaemon true) (.start))
    {:role role-name :thread t :running running :held? held? :starts starts}))

;; ── the storage ─────────────────────────────────────────────────────

(defn- unique-constraint-name
  "The ux_… index name out of a 23505's server message — best-effort:
  the 409 must stay honest when parsing fails."
  [^org.postgresql.util.PSQLException e]
  (some->> (.getMessage e)
           (re-find #"\"(ux_[a-z0-9_]+)\"")
           second))

(defn- table-for
  "The table kind lives in on this storage, or a problem naming the
  kind — never a nil spliced into SQL (\"SELECT * FROM  WHERE …\", a
  syntax error every pass, waymark-c631)."
  ^String [tables kind]
  (or (get @tables kind)
      (throw (ex-info (str "unknown kind: " (pr-str kind)
                           " has no table on this storage (never ensured here)")
                      {:waymark10/unknown-kind true :kind kind}))))

(defrecord PostgresStorage [^HikariDataSource ds tables]
  store/Storage
  (with-tx* [_ f]
    (jdbc/with-transaction [tx ds]
      (f tx)))

  (ensure-kind! [_ rmap]
    (with-open [conn (jdbc/get-connection ds)]
      (doseq [stmt (concat prerequisites (kind-ddl rmap))]
        (jdbc/execute! conn [stmt])))
    (swap! tables assoc (:kind rmap)
           (store/definition-checked-name (:plural rmap)))
    nil)

  (load-row [_ tx kind id opts]
    (let [table (table-for tables kind)
          sql (str "SELECT * FROM " table " WHERE id = ?"
                   (when (:for-update opts) " FOR UPDATE"))]
      (row->map (jdbc/execute-one! tx [sql id] jdbc-opts))))

  (insert-row! [_ tx kind row]
    (let [table (table-for tables kind)]
      (try
        (jdbc/execute-one!
         tx
         [(str "INSERT INTO " table
               " (id, state, version, data, shape, owner, law_revision, next_flip_at)"
               " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
          (:id row) (name (:state row)) (:version row 1)
          (jsonb (:data row)) (:shape row 1) (:owner row)
          (:law-revision row)
          (some-> ^java.time.Instant (:next-flip-at row) Timestamp/from)])
        (catch org.postgresql.util.PSQLException e
          (if (= "23505" (.getSQLState e))
            (throw (store/unique-violation kind (:id row)
                                           (unique-constraint-name e)))
            (throw e))))
      row))

  (save-row! [_ tx kind row expected-version]
    (let [table (table-for tables kind)
          res (try
                (jdbc/execute-one!
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
                 jdbc-opts)
                ;; a data edit can move a promoted generated column into
                ;; a declared-unique collision — the same honest refusal
                (catch org.postgresql.util.PSQLException e
                  (if (= "23505" (.getSQLState e))
                    (throw (store/unique-violation kind (:id row)
                                                   (unique-constraint-name e)))
                    (throw e))))]
      (when (nil? res)
        (throw (store/version-conflict kind (:id row) expected-version)))
      ;; the write's own stamp, so post-invoke envelopes never carry
      ;; the pre-write time
      (assoc row :updated-at (->inst (:updated_at res)))))

  (query-rows [_ tx kind where opts]
    (let [table (table-for tables kind)
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
                   ;; :newest-first — the LIMIT must bite the fresh
                   ;; end of a long table, not its oldest rows (the
                   ;; own-surface window, waymark-tti.3 L6). The
                   ;; keyword is the caller's, never the client's, and
                   ;; contributes no SQL text beyond this literal.
                   (when (:newest-first opts) " DESC")
                   " LIMIT " (long (:limit opts 100)))]
      (mapv row->map (jdbc/execute! tx (into [sql] params) jdbc-opts))))

  (external-ids [_ tx kind]
    (let [table (table-for tables kind)]
      (into []
            (keep :xid)
            (jdbc/execute! tx [(str "SELECT data->>'external_id' AS xid FROM "
                                    table
                                    " WHERE data->>'external_id' IS NOT NULL")]
                           jdbc-opts))))

  (append-transition! [_ tx record]
    ;; the floor is held before the id is allocated, and to the commit:
    ;; see the log's commit order
    ;; the outbox IS the log: the notify is in the append's statement and
    ;; so in the write transaction, and subscribers learn of exactly the
    ;; transitions that committed
    (let [res (jdbc/execute-one!
               tx
               [append-with-floor-sql
                (name (:kind record)) (:resource-id record)
                (name (:action record))
                (some-> (:from-state record) name)
                (name (:to-state record))
                (jsonb (:actor record))
                (:law-revision record)
                (:input-digest record)
                (some-> (:inputs record) jsonb)
                (some-> (:acknowledged record) not-empty jsonb)
                (some-> (:judgment record) not-empty jsonb)
                (some-> (:after record) jsonb)
                (:correlation-id record)
                (:idempotency-key record)
                (:summary record)
                notify-channel]
               jdbc-opts)]
      (assoc record :id (:id res) :at (->inst (:at res)))))

  (transitions [_ tx where opts]
    (let [read (fn [bound]
                 (let [clauses (cond-> []
                                 (:kind where) (conj ["kind = ?" (name (:kind where))])
                                 (:resource-id where) (conj ["resource_id = ?" (:resource-id where)])
                                 (:since where) (conj ["id > ?" (:since where)])
                                 bound (conj ["id <= ?" bound]))
                       sql (str "SELECT * FROM waymark10_transitions"
                                (when (seq clauses)
                                  (str " WHERE " (str/join " AND " (map first clauses))))
                                " ORDER BY id" (when (:newest-first opts) " DESC")
                                " LIMIT " (long (:limit opts 500)))]
                   (mapv transition->map
                         (jdbc/execute! tx (into [sql] (map second clauses)) jdbc-opts))))]
      (if (:settled opts)
        (settled-read tx read)
        (read nil))))

  (transitions-under-grant [_ tx grant-id since until opts]
    ;; the window bounds `at`, which ix_wm10_t_at serves; the grant is
    ;; then a filter over that slice of the log
    (let [clauses (cond-> [["actor->>'grant' = ?" (str grant-id)]]
                    since (conj ["at >= ?" (Timestamp/from ^java.time.Instant since)])
                    until (conj ["at <= ?" (Timestamp/from ^java.time.Instant until)]))
          sql (str "SELECT * FROM waymark10_transitions WHERE "
                   (str/join " AND " (map first clauses))
                   " ORDER BY id LIMIT " (long (:limit opts 500)))]
      (mapv transition->map
            (jdbc/execute! tx (into [sql] (map second clauses)) jdbc-opts))))

  (transition-stats [_ tx since include-system?]
    ;; the double AT TIME ZONE round-trip pins the bucket to the UTC
    ;; ISO week (store/utc-week-start's truncation) whatever the
    ;; session TimeZone says — desired and memory-twin buckets must
    ;; be the same instants
    (let [sql (str "SELECT date_trunc('week', at AT TIME ZONE 'UTC')"
                   " AT TIME ZONE 'UTC' AS week_start,"
                   " kind, action, actor->>'type' AS actor_type,"
                   " count(*) AS n"
                   " FROM waymark10_transitions"
                   " WHERE at >= ?"
                   (when-not include-system?
                     " AND coalesce(actor->>'type', '') <> 'system'")
                   " GROUP BY 1, 2, 3, 4"
                   " ORDER BY 1, 2, 3, 4")]
      (mapv (fn [r]
              {:week-start (->inst (:week_start r))
               :kind (:kind r)
               :action (:action r)
               :actor-type (:actor_type r)
               :n (long (:n r))})
            (jdbc/execute! tx [sql (Timestamp/from ^java.time.Instant since)]
                           jdbc-opts))))

  (transition-times [_ tx kind action since until conds limit]
    ;; the window walks ix_wm10_t_at; the where and the grant's
    ;; narrowing ride in as one semi-join over the kind's table, never
    ;; a query per row
    (let [table (table-for tables kind)
          parts (map cond-sql conds)
          sql (str "SELECT at FROM waymark10_transitions"
                   " WHERE kind = ? AND action = ? AND at >= ? AND at < ?"
                   " AND resource_id IN (SELECT id FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts))))
                   ") ORDER BY at LIMIT " (long limit))]
      (mapv (comp ->inst :at)
            (jdbc/execute! tx (-> [sql (name kind) (name action)
                                   (Timestamp/from ^java.time.Instant since)
                                   (Timestamp/from ^java.time.Instant until)]
                                  (into (mapcat second parts)))
                           jdbc-opts))))

  (corrections-by-model [_ tx actor-ids since excluded-kinds]
    ;; the lag is computed over the WHOLE log and filtered afterwards:
    ;; the transition a person corrects is routinely older than the
    ;; window that counts the correction, so narrowing before the
    ;; window function would drop exactly the pairs this counts
    (if (empty? actor-ids)
      []
      (let [marks (str/join ", " (repeat (count actor-ids) "?"))
            excluded (vec excluded-kinds)
            ex-marks (str/join ", " (repeat (count excluded) "?"))
            ;; a write a person ALLOWED (a held call's replay,
            ;; `allowed_by` on its actor) is that person's: it counts
            ;; as a correction, and is never one corrected
            sql (str "WITH walked AS ("
                     "SELECT at, kind, actor->>'type' AS actor_type,"
                     " actor->>'allowed_by' AS allowed_by,"
                     " lag(actor->>'id') OVER w AS prev_actor,"
                     " lag(actor->>'allowed_by') OVER w AS prev_allowed_by,"
                     " lag(actor->>'model') OVER w AS prev_model"
                     " FROM waymark10_transitions"
                     " WINDOW w AS (PARTITION BY kind, resource_id"
                     " ORDER BY id))"
                     " SELECT prev_model AS model, count(*) AS n"
                     " FROM walked"
                     " WHERE (actor_type = 'human' OR allowed_by IS NOT NULL)"
                     "   AND at >= ?"
                     "   AND prev_allowed_by IS NULL"
                     "   AND prev_actor IN (" marks ")"
                     (when (seq excluded)
                       (str "   AND kind NOT IN (" ex-marks ")"))
                     " GROUP BY 1 ORDER BY 1")]
        (mapv (fn [r] {:model (:model r) :n (long (:n r))})
              (jdbc/execute! tx (-> [sql (Timestamp/from ^java.time.Instant since)]
                                    (into (map str) actor-ids)
                                    (into excluded))
                             jdbc-opts)))))

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
    (let [table (table-for tables kind)]
      (:n (jdbc/execute-one!
           tx [(str "SELECT count(*) AS n FROM " table
                    " WHERE law_revision = ?")
               revision]
           jdbc-opts))))

  ;; ── phase 6: the maintainer's reads and the maintenance write ──────

  (count-matching [_ tx kind conds]
    (let [table (table-for tables kind)
          parts (map cond-sql conds)
          sql (str "SELECT count(*) AS n FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts)))))]
      (:n (jdbc/execute-one! tx (into [sql] (mapcat second parts)) jdbc-opts))))

  (sum-matching [_ tx kind of conds]
    (let [table (table-for tables kind)
          fname (store/definition-checked-name of)
          parts (map cond-sql conds)
          ;; un-coalesced deliberately: SUM over no contributions is
          ;; NULL, and the maintainer owns the empty default (0, or
          ;; absent under {:when-empty :absent})
          sql (str "SELECT SUM((data->>'" fname "')::numeric) AS s"
                   " FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts)))))]
      (:s (jdbc/execute-one! tx (into [sql] (mapcat second parts)) jdbc-opts))))

  (ids-matching [_ tx kind conds limit]
    (let [table (table-for tables kind)
          parts (map cond-sql conds)
          sql (str "SELECT id FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts))))
                   " ORDER BY id LIMIT " (long limit))]
      (mapv :id (jdbc/execute! tx (into [sql] (mapcat second parts)) jdbc-opts))))

  (update-data! [_ tx kind id data next-flip-at]
    (let [table (table-for tables kind)]
      (jdbc/execute-one!
       tx
       [(str "UPDATE " table
             " SET data = ?, next_flip_at = ?, updated_at = now()"
             " WHERE id = ?")
        (jsonb data)
        (some-> ^java.time.Instant next-flip-at Timestamp/from)
        id])
      nil))

  (delete-rows! [_ tx kind ids]
    (let [table (table-for tables kind)]
      ;; chunked, so a long purge never builds one statement with more
      ;; parameters than the wire takes
      (reduce (fn [n chunk]
                (+ (long n)
                   (long (or (:next.jdbc/update-count
                              (jdbc/execute-one!
                               tx
                               (into [(str "DELETE FROM " table " WHERE id IN ("
                                           (str/join ", " (repeat (count chunk) "?"))
                                           ")")]
                                     chunk)))
                             0))))
              0
              (partition-all 1000 (map str ids)))))

  (due-flips [_ tx kind now limit]
    (let [table (table-for tables kind)]
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

  (search-rows [_ tx kind conds {:keys [order-by desc then-by limit offset]}]
    (let [table (table-for tables kind)
          parts (map cond-sql conds)
          column (fn [f]
                   (cond
                     (nil? f) "created_at"
                     (= :state f) "state"
                     ;; the engine's own timestamps are columns already —
                     ;; they promote nothing, so there is no f_ twin
                     (contains? store/sortable-timestamps f)
                     (store/definition-checked-name f)
                     :else (str "f_" (store/definition-checked-name f))))
          order (str/join ", "
                          (map (fn [{:keys [field desc]}]
                                 (str (column field) (when desc " DESC")))
                               (cons {:field order-by :desc desc} then-by)))
          sql (str "SELECT * FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts))))
                   " ORDER BY " order ", id"
                   " LIMIT " (long (or limit 100))
                   " OFFSET " (long (or offset 0)))]
      (mapv row->map (jdbc/execute! tx (into [sql] (mapcat second parts))
                                    jdbc-opts))))

  (facet-counts [_ tx kind field conds array? absent-as]
    (let [table (table-for tables kind)
          parts (map cond-sql conds)
          ;; a scalar field's absent rows count under the declared value
          absent (when-not (or (= :state field) array?) absent-as)
          expr (cond
                 (= :state field) "state"
                 ;; rows without the array carry JSON null (a scalar —
                 ;; jsonb_array_elements_text refuses it); they count
                 ;; toward no facet value, exactly like a scalar NULL
                 array? (let [f (store/definition-checked-name field)]
                          (str "jsonb_array_elements_text(CASE WHEN"
                               " jsonb_typeof(data->'" f "') = 'array'"
                               " THEN data->'" f "' ELSE '[]'::jsonb END)"))
                 :else (let [col (str "data->>'"
                                      (store/definition-checked-name field)
                                      "'")]
                         (if (some? absent)
                           (str "coalesce(" col ", ?)")
                           col)))
          sql (str "SELECT " expr " AS v, count(*) AS n FROM " table
                   (when (seq parts)
                     (str " WHERE " (str/join " AND " (map first parts))))
                   " GROUP BY 1 ORDER BY 1")]
      (into (sorted-map)
            (keep (fn [r] (when (some? (:v r)) [(:v r) (:n r)])))
            (jdbc/execute! tx (into (cond-> [sql] (some? absent) (conj absent))
                                    (mapcat second parts))
                           jdbc-opts))))

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

  (elect-role! [_ role-name opts]
    (elect! ds role-name opts))

  (release-role! [_ {:keys [running ^Thread thread]}]
    (reset! running false)
    (some-> thread .interrupt)
    (some-> thread (.join 5000))
    nil)

  (restamp-law! [_ tx kind where to-revision]
    (let [table (table-for tables kind)
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

;; ── a LISTEN connection that comes back ─────────────────────────────
;; A parked LISTEN connection dies with a database restart, a network
;; blip or an idle cut, and getNotifications then throws on every call.
;; A listener holds that connection for one loop: it answers the beat
;; instead of throwing, reopens the connection with every LISTEN the
;; loop needs, and says the loss and the recovery once each.

(defn listen-on
  "A dedicated raw JDBC connection LISTENing every channel named —
  listen-connection's discipline (never from the pool) for a loop that
  names its own channels. The caller owns closing it."
  ^Connection [^PostgresStorage st channels]
  (let [url (.getJdbcUrl ^HikariDataSource (:ds st))
        conn (DriverManager/getConnection url)]
    (try
      (with-open [stmt (.createStatement conn)]
        (doseq [ch channels]
          (.execute stmt (str "LISTEN " ch))))
      conn
      (catch Exception e
        (try (.close conn) (catch Exception _ nil))
        (throw e)))))

(defn- listener-open!
  "One attempt to open the listener's connection; true when it holds
  one after it. A failure doubles the wait before the next attempt,
  1s up to 30s; only the first failure and the recovery are said."
  [{:keys [storage channels warn state closed]}]
  (try
    (let [conn (listen-on storage channels)
          was-lost? (:lost? @state)]
      (swap! state assoc :conn conn :lost? false :backoff-ms 0)
      (if @closed
        ;; a stop raced the open: nobody would close this one later
        (.close conn)
        (when was-lost? (warn "LISTEN connection is back")))
      true)
    (catch Exception e
      (let [{:keys [lost? backoff-ms]} @state
            wait-ms (min 30000 (max 1000 (* 2 (long backoff-ms))))]
        (when-not lost?
          (warn "no LISTEN connection (" (ex-message e)
                "); riding the poll until it is back"))
        (swap! state assoc :conn nil :lost? true :backoff-ms wait-ms
               :retry-at (+ (System/currentTimeMillis) wait-ms)))
      false)))

(defn listener
  "A LISTEN connection on `channels` that reopens itself, for one
  loop's thread. warn is the loop's own (fn [& parts]). nil on a
  storage that is not Postgres. A first open that fails is retried
  like a lost one. close-listener! ends it."
  [st channels warn]
  (when (instance? PostgresStorage st)
    (let [l {:storage st
             :channels (vec channels)
             :warn warn
             :state (atom {:conn nil :lost? false :backoff-ms 0 :retry-at 0})
             :closed (atom false)}]
      (listener-open! l)
      l)))

(defn listener-connection
  "The connection the listener holds right now, or nil while it is
  lost."
  ^Connection [l]
  (:conn @(:state l)))

(defn await-notifications!
  "The loop's wait: the notifications that arrived within timeout-ms,
  or nil. It never throws for a dead connection — it closes it, says
  so once and answers at once, so the caller's beat still runs; later
  calls sleep the poll and reopen the connection when its backoff is
  due. A reopen answers at once too, so the beat catches up on what
  the gap missed."
  [{:keys [state warn closed] :as l} timeout-ms]
  (let [timeout-ms (long timeout-ms)]
    (if-some [^Connection conn (:conn @state)]
      (try
        (vec (.getNotifications ^PGConnection (.unwrap conn PGConnection)
                                (int timeout-ms)))
        (catch Exception e
          (try (.close conn) (catch Exception _ nil))
          (swap! state assoc :conn nil :lost? true :backoff-ms 1000
                 :retry-at (+ (System/currentTimeMillis) 1000))
          (when-not @closed
            (warn "LISTEN connection lost (" (ex-message e)
                  "); riding the poll until it is back"))
          nil))
      (let [wait-ms (- (long (:retry-at @state)) (System/currentTimeMillis))]
        (cond
          @closed (Thread/sleep timeout-ms)
          (pos? wait-ms) (Thread/sleep (long (min timeout-ms wait-ms)))
          (not (listener-open! l)) (Thread/sleep timeout-ms))
        nil))))

(defn close-listener!
  "End a listener: it reopens nothing after this. nil-safe."
  [l]
  (when l
    (reset! (:closed l) true)
    (when-some [^Connection conn (:conn @(:state l))]
      (try (.close conn) (catch Exception _ nil))))
  nil)

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
