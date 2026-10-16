(ns waymark10.server.pull-fires
  "The fires a pull runner link holds until a localfire takes them
  (pull-mode epic 54360702, piece 1a), kept in Postgres so a fire
  outlives the process that queued it.

  A pull link has no fire URL: the engine cannot POST to a laptop
  behind a router. So a fire through one is a row here — the link, the
  schedule or model it is for, when it was made, its lease and its
  text. The text carries a one-time Key (spec-seat R-12.11), so it is
  sealed with at-rest/seal as a secret field is, and opened only by
  `take!`, when it is handed out.

  THE LEASE. `take!` stamps `handed_out_at`; a fire handed out more
  than `lease-seconds` ago and never answered is handed out again.
  `finish!` removes an answered fire. `sweep!` removes every fire older
  than its cutoff; the caller records those as missed.

  POLLS. `poll!` records that a localfire asked for a link's fires;
  `last-poll` reads it. Piece 1b's routes write it.

  The tables are ensured on first use per storage, as the MCP session
  table is (mcp_sessions.clj): engine state, not a kind. The in-memory
  twin keeps none — `last-poll` answers nil there, so a pull link
  reads waiting, and a fire through one refuses."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.server.at-rest :as at-rest]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres])
  (:import (java.sql Timestamp)
           (java.time Instant OffsetDateTime)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def lease-seconds
  "How long a fire handed out stays held before it is handed out again."
  300)

(def keep-seconds
  "How long a fire waits for a localfire before it is dropped as missed."
  86400)

(def ^:private jdbc-opts {:builder-fn rs/as-unqualified-lower-maps})

(def ^:private ddl
  ["CREATE TABLE IF NOT EXISTS waymark10_pull_fires (
      id text PRIMARY KEY,
      seq bigserial,
      link_id text NOT NULL,
      for_kind text,
      for_id text,
      created_at timestamptz NOT NULL,
      handed_out_at timestamptz,
      text text NOT NULL)"
   "CREATE INDEX IF NOT EXISTS ix_wm10_pull_fires_link
      ON waymark10_pull_fires (link_id, created_at, seq)"
   "CREATE TABLE IF NOT EXISTS waymark10_pull_polls (
      link_id text PRIMARY KEY,
      polled_at timestamptz NOT NULL)"])

(defn postgres?
  "Does this storage keep the tables? The in-memory twin does not."
  [storage]
  (instance? waymark10.server.store.postgres.PostgresStorage storage))

(defonce ^:private ensured (atom #{}))

(defn- ensure! [storage tx]
  (when-not (postgres? storage)
    (throw (ex-info "pull fires: this storage keeps no fires; a pull link needs Postgres"
                    {:pull-fires :no-table})))
  (when-not (contains? @ensured storage)
    (doseq [sql ddl]
      (jdbc/execute! tx [sql]))
    (swap! ensured conj storage)))

(defn- ts ^Timestamp [^Instant i] (Timestamp/from i))

(defn- instant ^Instant [t]
  (cond
    (instance? Timestamp t) (.toInstant ^Timestamp t)
    (instance? OffsetDateTime t) (.toInstant ^OffsetDateTime t)
    :else t))

(defn- fire-of [r]
  (when r
    {:id (:id r)
     :link_id (:link_id r)
     :for_kind (:for_kind r)
     :for_id (:for_id r)
     :created_at (instant (:created_at r))
     :handed_out_at (some-> (:handed_out_at r) instant)
     :text (:text r)}))

(defn enqueue!
  "Hold one fire of `link-id` for `for-kind`/`for-id` (\"schedule\" and
  its id, or nil), made at `now`, its `text` sealed. → its id. With no
  data key set the seal throws: the text is never written plain."
  [storage {:keys [link-id for-kind for-id text now]}]
  (let [id (str (UUID/randomUUID))
        sealed (at-rest/seal (str text))]
    (store/with-tx storage
      (fn [tx]
        (ensure! storage tx)
        (jdbc/execute! tx ["INSERT INTO waymark10_pull_fires
                              (id, link_id, for_kind, for_id, created_at, text)
                            VALUES (?, ?, ?, ?, ?, ?)"
                           id (str link-id) (some-> for-kind name)
                           (some-> for-id str) (ts now) sealed])))
    id))

(defn pending
  "Every fire `link-id` holds, oldest first, its text still sealed."
  [storage link-id]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (mapv fire-of
            (jdbc/execute! tx ["SELECT * FROM waymark10_pull_fires
                                WHERE link_id = ? ORDER BY created_at, seq"
                               (str link-id)]
                           jdbc-opts)))))

(defn take!
  "The oldest fire of `link-id` nobody holds — never handed out, or
  handed out `lease-seconds` or more before `now` — leased at `now`,
  its text opened. nil when none waits."
  [storage link-id ^Instant now]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (some-> (jdbc/execute-one!
               tx ["UPDATE waymark10_pull_fires SET handed_out_at = ?
                     WHERE id = (SELECT id FROM waymark10_pull_fires
                                  WHERE link_id = ?
                                    AND (handed_out_at IS NULL OR handed_out_at <= ?)
                                  ORDER BY created_at, seq
                                  LIMIT 1 FOR UPDATE SKIP LOCKED)
                     RETURNING *"
                   (ts now) (str link-id) (ts (.minusSeconds now lease-seconds))]
               jdbc-opts)
              fire-of
              (update :text at-rest/open)))))

(defn finish!
  "Remove the fire `id`, answered. → the fire as it stood, its text
  sealed, or nil when no such fire waits."
  [storage id]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (fire-of (jdbc/execute-one! tx ["DELETE FROM waymark10_pull_fires
                                       WHERE id = ? RETURNING *" (str id)]
                                  jdbc-opts)))))

(defn sweep!
  "Remove every fire made before `cutoff`. → those fires, texts sealed."
  [storage ^Instant cutoff]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (mapv fire-of
            (jdbc/execute! tx ["DELETE FROM waymark10_pull_fires
                                WHERE created_at < ? RETURNING *" (ts cutoff)]
                           jdbc-opts)))))

(defn poll!
  "Record that a localfire asked for `link-id`'s fires at `now`."
  [storage link-id ^Instant now]
  (store/with-tx storage
    (fn [tx]
      (ensure! storage tx)
      (jdbc/execute! tx ["INSERT INTO waymark10_pull_polls (link_id, polled_at)
                          VALUES (?, ?)
                          ON CONFLICT (link_id) DO UPDATE SET polled_at = EXCLUDED.polled_at"
                         (str link-id) (ts now)])))
  nil)

(defn last-poll
  "When a localfire last asked for `link-id`'s fires, or nil — always
  nil over a storage that keeps no table."
  ^Instant [storage link-id]
  (when (postgres? storage)
    (store/with-tx storage
      (fn [tx]
        (ensure! storage tx)
        (some-> (jdbc/execute-one! tx ["SELECT polled_at FROM waymark10_pull_polls
                                         WHERE link_id = ?" (str link-id)]
                                   jdbc-opts)
                :polled_at
                instant)))))
