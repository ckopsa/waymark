(ns waymark10.server.mcp-sessions
  "The MCP transport's session table, kept in Postgres so a session
  outlives the process that minted it (spec-seat.md R-12.14).

  WHY A TABLE. The deploy is start-before-stop: the new allocation
  comes up beside the old one and traffic moves across. While the
  sessions lived in an atom, every client's next message carried an id
  the new process had never seen, so it answered the protocol's 404,
  the connector reported the server as not connected, and the seat a
  `waymark_sit` had welded to the session was lost with it. One row per
  session in the database both allocations share makes the id, and the
  binding on it, known to whichever process the message reaches.

  WHAT IS STORED. Never the id itself: `id_hash` is the SHA-256 of it,
  hex. The id is 128 bits of real randomness, so the hash needs no
  salt, and a read of this table hands nobody a session to present.
  Beside it `created`, `touched`, the bound seat and sitting as plain
  columns a person can query, and `binding`, the whole binding as EDN
  (the sitter principal, the bench) so it reads back as the same value
  `bind!` was given.

  THE CLOCK. The TTL and the lazy eviction are the caller's
  (mcp/session-ttl-seconds): `open!` sweeps every expired row and
  `touch!` evicts the one it finds expired. `touched` is written at
  most once a minute per session (`touch-every-seconds`), so a busy
  session is not a write per message; against an eight-hour TTL the
  minute is noise.

  The table is ensured on first use per storage, as the observations
  outbox is (events.clj), because it is engine state and not a kind:
  no declaration names it and the migrate planner never sees it."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.sql Timestamp)
           (java.time Instant OffsetDateTime)))

(set! *warn-on-reflection* true)

(def touch-every-seconds
  "The least time between two writes of one session's `touched`."
  60)

(def ^:private jdbc-opts {:builder-fn rs/as-unqualified-lower-maps})

(def ^:private ddl
  ["CREATE TABLE IF NOT EXISTS waymark10_mcp_sessions (
      id_hash text PRIMARY KEY,
      created timestamptz NOT NULL,
      touched timestamptz NOT NULL,
      bound_seat text,
      bound_sitting text,
      binding text)"
   ;; what the client declared at initialize (docs/spec-mcp-apps.md § 5)
   "ALTER TABLE waymark10_mcp_sessions
      ADD COLUMN IF NOT EXISTS app_ui boolean NOT NULL DEFAULT false"
   "ALTER TABLE waymark10_mcp_sessions ADD COLUMN IF NOT EXISTS client_name text"
   "ALTER TABLE waymark10_mcp_sessions ADD COLUMN IF NOT EXISTS client_version text"
   "CREATE INDEX IF NOT EXISTS ix_wm10_mcp_sessions_touched
      ON waymark10_mcp_sessions (touched)"])

(defn postgres?
  "Does this storage keep the table? The in-memory twin does not, and
  an engine over it keeps its sessions in the atom as it always has."
  [storage]
  (instance? waymark10.server.store.postgres.PostgresStorage storage))

(defonce ^:private ensured (atom #{}))

(defn- ensure! [storage tx]
  (when-not (contains? @ensured storage)
    (doseq [sql ddl]
      (jdbc/execute! tx [sql]))
    (swap! ensured conj storage)))

(defn id-hash
  "The SHA-256 of a session id, lowercase hex: the only spelling of the
  id this table ever holds."
  [id]
  (let [d (.digest (MessageDigest/getInstance "SHA-256")
                   (.getBytes (str id) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (long %) 0xff)) d))))

;; ── the binding as text ─────────────────────────────────────────────

(defn- write-binding [b]
  (when (some? b)
    (binding [*print-length* nil
              *print-level* nil
              *print-meta* false
              *print-namespace-maps* false]
      (pr-str (walk/postwalk
               #(if (instance? Instant %)
                  (tagged-literal 'waymark10/instant (str %))
                  %)
               b)))))

(defn- read-binding [s]
  (when (some? s)
    (edn/read-string {:readers {'waymark10/instant #(Instant/parse %)}
                      :default tagged-literal}
                     s)))

;; ── time across the wire ────────────────────────────────────────────

(defn- ts ^Timestamp [^Instant i] (Timestamp/from i))

(defn- instant ^Instant [t]
  (cond
    (instance? Timestamp t) (.toInstant ^Timestamp t)
    (instance? OffsetDateTime t) (.toInstant ^OffsetDateTime t)
    :else t))

;; ── the table's verbs ───────────────────────────────────────────────

(defn open!
  "Insert a fresh session under `id`, touched now, after sweeping every
  row untouched since `cutoff`. Answers the id. `declared` is {:app-ui
  :client-name :client-version}; nil or a missing key writes false / NULL."
  [storage id ^Instant now ^Instant cutoff & [declared]]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (jdbc/execute! tx ["DELETE FROM waymark10_mcp_sessions WHERE touched < ?"
                         (ts cutoff)])
      (jdbc/execute! tx ["INSERT INTO waymark10_mcp_sessions
                            (id_hash, created, touched,
                             app_ui, client_name, client_version)
                          VALUES (?, ?, ?, ?, ?, ?)"
                         (id-hash id) (ts now) (ts now)
                         (boolean (:app-ui declared))
                         (some-> (:client-name declared) str)
                         (some-> (:client-version declared) str)])))
  id)

(defn touch!
  "The entry `id` names, {:created :touched :bound :app-ui :client-name
  :client-version}, with `touched`
  moved to now when it is older than `touch-every-seconds` — or nil
  when no row answers the id, or the row was untouched since `cutoff`,
  in which case it is evicted here."
  [storage id ^Instant now ^Instant cutoff]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (let [h (id-hash id)
            row (jdbc/execute-one!
                 tx ["SELECT created, touched, binding,
                            app_ui, client_name, client_version
                        FROM waymark10_mcp_sessions WHERE id_hash = ?" h]
                 jdbc-opts)]
        (when row
          (let [touched (instant (:touched row))]
            (if (neg? (compare touched cutoff))
              (do (jdbc/execute! tx ["DELETE FROM waymark10_mcp_sessions
                                       WHERE id_hash = ?" h])
                  nil)
              (let [stale? (neg? (compare touched
                                          (.minusSeconds now touch-every-seconds)))]
                (when stale?
                  (jdbc/execute! tx ["UPDATE waymark10_mcp_sessions
                                        SET touched = ? WHERE id_hash = ?"
                                     (ts now) h]))
                {:created (instant (:created row))
                 :touched (if stale? now touched)
                 :bound (read-binding (:binding row))
                 :app-ui (boolean (:app_ui row))
                 :client-name (:client_name row)
                 :client-version (:client_version row)}))))))))

(defn bind!
  "Write `binding` onto the session `id` names, whole — a second bind
  overwrites the first. A session no row answers is left alone."
  [storage id binding]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (jdbc/execute! tx ["UPDATE waymark10_mcp_sessions
                            SET binding = ?, bound_seat = ?, bound_sitting = ?
                          WHERE id_hash = ?"
                         (write-binding binding)
                         (some-> (:seat binding) str)
                         (some-> (:sitting binding) str)
                         (id-hash id)]))))

(defn binding-of
  "The binding on the session `id` names, or nil. A plain read: no
  touch, no eviction."
  [storage id]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (some-> (jdbc/execute-one!
               tx ["SELECT binding FROM waymark10_mcp_sessions
                     WHERE id_hash = ?" (id-hash id)]
               jdbc-opts)
              :binding
              read-binding))))

(defn bench!
  "Put {:repo :branch} on the binding of the session `id` names, under
  :bench. A session with no binding is left untouched."
  [storage id repo branch]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (let [h (id-hash id)]
        (when-some [b (some-> (jdbc/execute-one!
                               tx ["SELECT binding FROM waymark10_mcp_sessions
                                     WHERE id_hash = ? FOR UPDATE" h]
                               jdbc-opts)
                              :binding
                              read-binding)]
          (jdbc/execute! tx ["UPDATE waymark10_mcp_sessions
                                SET binding = ? WHERE id_hash = ?"
                             (write-binding (assoc b :bench {:repo repo
                                                             :branch branch}))
                             h]))))))

(defn live-hashes
  "The id hashes of every session touched since `since`, as a set. A
  plain read: no touch, no eviction."
  [storage ^Instant since]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (into #{}
            (keep :id_hash)
            (jdbc/execute!
             tx ["SELECT id_hash FROM waymark10_mcp_sessions WHERE touched >= ?"
                 (ts since)]
             jdbc-opts)))))

(defn bound-elsewhere
  "Which of `sitting-ids` a session OTHER than `id` is bound to, as a
  set. Only sessions touched since `cutoff` count: an older row is one
  the next sweep takes. A plain read: no touch, no eviction."
  [storage id sitting-ids ^Instant cutoff]
  (let [ids (vec (distinct (keep #(some-> % str not-empty) sitting-ids)))]
    (if (empty? ids)
      #{}
      (store/with-tx storage
        (fn [tx]
          (ensure! storage tx)
          (into #{}
                (keep :bound_sitting)
                (jdbc/execute!
                 tx (into [(str "SELECT DISTINCT bound_sitting"
                                " FROM waymark10_mcp_sessions"
                                " WHERE id_hash <> ? AND touched >= ?"
                                " AND bound_sitting IN ("
                                (apply str (interpose ", " (repeat (count ids) "?")))
                                ")")
                           (id-hash id) (ts cutoff)]
                          ids)
                 jdbc-opts)))))))
