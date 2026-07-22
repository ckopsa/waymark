(ns waymark10.server.store.migrate
  "The schema-evolution planner: declared shape vs live shape, as an
  ordered list of executable steps (waymark9 server/migrate.py's
  snapshot diff, collapsed to plan/apply — no SQL files, because the
  taming insight makes review-by-hand unnecessary for columns).

  The insight this design stands on: in waymark10 ALL row data lives
  in the JSONB document; every per-kind column beyond the engine's
  fixed set is a GENERATED column derived from that document. So
  dropping or recreating a promoted column is ALWAYS data-safe —
  Postgres backfills a generated column on ADD, and a dropped one is
  regenerable from the document it derived from. The planner is
  therefore aggressive about column reconciliation and conservative
  about exactly one thing: UPDATEs that rewrite state tokens
  (:rename-state), the only steps marked :destructive? true.

  Recorded boundaries, each a sentence:
  - Generated-column EXPRESSION drift is invisible: Postgres
    normalizes stored expressions beyond honest comparison, so drift
    compares by column name + data type only — acceptable because the
    expression derives mechanically from (field, type) and cannot
    move unless one of them did.
  - Engine tables (waymark10_transitions/idempotency/drafts/cursors/
    job_leases) reconcile ADDITIVELY only — missing tables, columns,
    indexes are created; engine-column drops/retypes are out of scope
    (those columns hold real data, not derivations).
  - A live NON-generated column the projection does not declare is
    left standing, unlisted: only f_* columns are known-derived; a
    hand-added column is someone's data, not the planner's to drop.
  - A type retype on an engine/fixed column emits nothing (same
    boundary as engine tables); the drift stays visible in
    table-snapshot for a human.

  Step shape: {:kind :create-table|:add-column|:drop-column|
  :recreate-column|:add-index|:drop-index|:rename-state
  :table … :sql … :destructive? bool :reason \"one sentence\"}."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- step [kind table sql destructive? reason]
  {:kind kind :table table :sql sql
   :destructive? (boolean destructive?) :reason reason})

;; ── the rename chain ────────────────────────────────────────────────

(defn resolve-token
  "One token read FORWARD through a rename map ({old-kw new-kw}) to
  its terminal spelling; a cycle stops at the revisit (the checks
  refuse cycles at declaration time — this is belt over braces)."
  [renames tok]
  (loop [cur (name tok) seen #{}]
    (if-some [nxt (and (not (contains? seen cur))
                       (get renames (keyword cur)))]
      (recur (name nxt) (conj seen cur))
      cur)))

;; ── one table's reconciliation ──────────────────────────────────────

(defn- create-steps [proj reason]
  (let [[create & indexes] (pg/table-ddl proj)]
    (into [(step :create-table (:table proj) create false reason)]
          (map #(step :add-index (:table proj) % false
                      "a standard index of a table being created"))
          indexes)))

(defn- column-steps
  "Reconcile one live table's columns against the projection.
  drop-derived? true (kind tables) also drops/recreates f_* columns;
  false (engine tables) reconciles additively only."
  [{:keys [table columns]} live drop-derived?]
  (let [live-cols (:columns live)
        desired-by-name (into {} (map (juxt :name identity)) columns)
        adds (for [{col :name :keys [ddl generated?]} columns
                   :when (not (contains? live-cols col))]
               (step :add-column table
                     (str "ALTER TABLE " table " ADD COLUMN " ddl)
                     false
                     (if generated?
                       (str col " is a declared promotion with no live column; Postgres backfills generated columns on ADD.")
                       (str col " is an engine column the live table predates."))))
        recreates
        (when drop-derived?
          (for [{col :name :keys [ddl type]} columns
                :let [lv (get live-cols col)]
                :when (and lv
                           (str/starts-with? col "f_")
                           (not= type (:type lv)))
                s [(step :recreate-column table
                         (str "ALTER TABLE " table " DROP COLUMN " col)
                         false
                         (str col " retypes " (:type lv) " → " type
                              ": drop the stale generated column (derived data, regenerable)."))
                   (step :recreate-column table
                         (str "ALTER TABLE " table " ADD COLUMN " ddl)
                         false
                         (str col " retypes " (:type lv) " → " type
                              ": re-add at the declared type; Postgres backfills."))]]
            s))
        drops
        (when drop-derived?
          (for [[col lv] (sort-by key live-cols)
                :when (and (str/starts-with? col "f_")
                           (:generated? lv)
                           (not (contains? desired-by-name col)))]
            (step :drop-column table
                  (str "ALTER TABLE " table " DROP COLUMN " col)
                  false
                  (str "no declaration promotes " col
                       "; the column is derived data, regenerable from the document."))))]
    (concat adds recreates drops)))

(defn- index-steps
  "The engine's standard indexes by NAME (Postgres normalizes index
  definitions past honest text comparison): declared names missing
  live are created; live ix_* names on the table that no declaration
  claims are dropped (an index is derived data by construction)."
  [{:keys [table indexes]} live drop-derived?]
  (let [live-ix (:indexes live)]
    (concat
     (for [[ix-name sql] (sort-by key indexes)
           :when (not (contains? live-ix ix-name))]
       (step :add-index table sql false
             (str ix-name " is a declared index with no live twin.")))
     (when drop-derived?
       (for [[ix-name _] (sort-by key live-ix)
             ;; only names this table's OWN derived patterns could have
             ;; minted — anchored on the table name, so a table that
             ;; happens to start with ix/ux never loses its pkey
             :when (and (or (str/starts-with? ix-name (str "ix_" table "_"))
                            ;; declared-unique indexes are derived from
                            ;; the declaration exactly like ix_ ones —
                            ;; an undeclared :unique reconciles away
                            (str/starts-with? ix-name (str "ux_" table "_")))
                        (not (contains? indexes ix-name)))]
         (step :drop-index table
               (str "DROP INDEX IF EXISTS " ix-name)
               false
               (str "no declaration claims index " ix-name ".")))))))

(defn- rename-steps
  "The one destructive class: an UPDATE that rewrites state tokens.
  Emitted only for live tokens the declaration no longer names but a
  :renames {:states …} entry maps (directly or through the chain)."
  [st rmap table]
  (let [renames (get-in rmap [:renames :states])
        declared (into #{} (map name) (:states rmap))]
    (when (seq renames)
      (when-some [live-states (pg/distinct-states st table)]
        (for [[old _] (sort-by key renames)
              :let [o (store/definition-checked-name old)
                    target (resolve-token renames old)]
              :when (and (contains? live-states o)
                         (not (contains? declared o)))]
          (step :rename-state table
                (str "UPDATE " table " SET state = '"
                     (store/definition-checked-name target)
                     "', updated_at = now() WHERE state = '" o "'")
                true
                (str "live rows occupy retired state " o " → " target
                     " — an UPDATE that rewrites state tokens.")))))))

(defn- plan-table
  "One projection against its live table. drop-derived? true for kind
  tables (f_* columns and ix_* indexes reconcile aggressively), false
  for engine tables (additive only). rmap non-nil adds the
  state-rename steps."
  [st proj live drop-derived? rmap]
  (if (nil? live)
    (create-steps proj
                  (str "table " (:table proj) " does not exist; the full kind DDL is one CREATE."))
    (concat (column-steps proj live drop-derived?)
            (index-steps proj live drop-derived?)
            (when rmap (rename-steps st rmap (:table proj))))))

;; ── the plan ────────────────────────────────────────────────────────

(defn plan
  "Ordered steps taking the live database to the declared shape:
  every kind in resources (normalized declarations — the registry's
  rdefs), then the engine's own tables (additive-only). Empty when
  the database already matches — the boot's green light."
  [st resources]
  (vec
   (concat
    (mapcat (fn [rmap]
              (let [proj (store/kind-projection rmap)]
                (plan-table st proj
                            (pg/table-snapshot st (:table proj))
                            true rmap)))
            (sort-by (comp name :kind) resources))
    (mapcat (fn [proj]
              (plan-table st proj
                          (pg/table-snapshot st (:table proj))
                          false nil))
            pg/engine-projections))))

(defn describe
  "One step as one line — the boot refusal, the CLI, and the design
  doc all speak it."
  [{:keys [kind table sql destructive? reason]}]
  (str (when destructive? "[DESTRUCTIVE] ")
       (name kind) " " table ": " sql "  -- " reason))

;; ── apply ───────────────────────────────────────────────────────────

(defn apply!
  "Execute the plan's steps in order. Steps marked :destructive? are
  SKIPPED (and returned under :skipped) unless {:destructive? true}
  opts them in — rewriting state tokens is the one thing this planner
  never does silently. → {:applied [step …] :skipped [step …]}."
  [st steps {:keys [destructive?]}]
  (let [{skipped true to-apply false}
        (group-by #(boolean (and (:destructive? %) (not destructive?)))
                  steps)]
    (with-open [conn (jdbc/get-connection
                      ^com.zaxxer.hikari.HikariDataSource (:ds st))]
      ;; a virgin database first: the helper functions the generated
      ;; columns call and the engine's own tables — idempotent, the
      ;; same prerequisites ensure-kind! runs at boot (design §24;
      ;; found by the mealplan10 cutover's fresh production db)
      (doseq [stmt pg/prerequisites]
        (jdbc/execute! conn [stmt]))
      (doseq [s to-apply]
        (jdbc/execute! conn [(:sql s)])))
    {:applied (vec to-apply) :skipped (vec skipped)}))

;; ── the boot's state-token gate (waymark9's check_state_tokens) ─────

(defn assert-known-states!
  "REFUSE to serve when any kind's live rows occupy a state token the
  declaration neither names nor maps through :renames {:states …} —
  a row the machine cannot judge is worse than a boot that stops.
  The fix is named: declare :renames or migrate."
  [st resources]
  (doseq [rmap (sort-by (comp name :kind) resources)]
    (let [table (store/definition-checked-name (:plural rmap))
          known (into (into #{} (map name) (:states rmap))
                      (map name)
                      (keys (get-in rmap [:renames :states])))
          unmapped (seq (remove known (or (pg/distinct-states st table) #{})))]
      (when unmapped
        (throw (t/definition-error
                (str (name (:kind rmap)) ": live rows occupy state token(s) "
                     (vec unmapped) " that no declaration maps — declare "
                     ":renames {:states {" (str/join " " (map #(str ":" % " :<current>") unmapped))
                     "}} or migrate the rows before boot")
                {:check :state-tokens
                 :kind (:kind rmap)
                 :states (vec unmapped)}))))))
