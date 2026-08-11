(ns mealplan10.migrate-v9
  "The one-time data migration: the family's live mealplan9 (Python,
  waymark9) rows into mealplan10's database. A document copy, not a
  replay — the kinds, states, and field vocabulary are identical by
  construction (the pantry-prices parity port), so each row's JSONB
  document decodes through the v10 schema, validates CLOSED (the
  residue rule: an undeclared key is reported, never silently
  dropped), materializes its pure derived facts, and inserts with its
  id, state, version, and owner intact. One synthetic :create
  transition records the birth (from-state nil, the migrate-v9 system
  actor, nil law revision — the pre-law horizon replay-history
  already skips). Aggregates and the clock index land in a
  maintainer/backfill! pass per kind, and every row then holds under
  the current law.

  The event kind is NOT migrated: it is a mirror — discovery re-mints
  the calendar from the ICS feed on first boot, which is the honest
  source.

      SOURCE_DSN=jdbc:postgresql://…/mealplan9?user=… \\
      MEALPLAN10_DSN=jdbc:postgresql://…/mealplan10?user=… \\
      clojure -M:migrate-v9            # dry run: read, validate, report
      APPLY=1 clojure -M:migrate-v9    # execute

  The dry run reads the source and validates every row without
  writing; it exits 1 on any violation, naming each row and field.
  Run `make migrate10 APPLY=1` against the target FIRST (the tables
  must exist), and take a pg_dump of the SOURCE before APPLY — the
  script never writes to the source, but the cutover it serves has no
  other rollback for the window's writes."
  (:require [mealplan10.main :as main]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.derived :as derived]
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.summary :as summary]
            [waymark10.wire :as wire])
  (:gen-class))

(def kinds-in-order
  "Every kind but :event (the mirror re-discovers). Order is cosmetic
  — refs are ids in JSONB, never FK constraints. :plan carries ONE
  recorded transform (the days fan-out below); :plan_day has no v9
  table — its rows are born from the plans' shed :days."
  [:rotation :meal :ingredient :plan :plan_day :grocery_list :prep_task
   :product :substitution :meal_line])

(defn- source-rows [source-db table]
  (jdbc/execute! source-db
                 [(str "SELECT id, state, version, data, shape, owner FROM "
                       table " ORDER BY id")]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn- violations-of
  "The residue check: every undeclared key and mistyped value, named.
  nil when the document is exactly the declared shape."
  [rdef doc]
  (schema/closed-errors (:schema rdef) doc))

;; ── the one recorded transform: v9 plans shed :days ─────────────────
;; The promoted plan_day kind holds the week now; a v9 plan document
;; arriving with :days intact would be residue (the rule working), so
;; the plan pass sheds them — and each shed day births a plan_day row,
;; its STATE derived from the day's own facts (meal_id → planned,
;; eating_out → eating_out, else undecided; the bool itself drops —
;; state is the fact).

(defn- day->plan-day
  "One raw (wire-shaped) v9 day → a plan_day row spec."
  [day-rdef plan-id owner raw-day]
  {:id (str (random-uuid))
   :state (cond
            (some? (:meal_id raw-day)) :planned
            (true? (:eating_out raw-day)) :eating_out
            :else :undecided)
   :version 1
   :owner owner
   :doc (schema/decode (:schema day-rdef)
                       (-> raw-day
                           (dissoc :eating_out)
                           (assoc :plan_id plan-id)))})

(def ^:private fanned-days
  ;; the plan pass's shed days, handed to the :plan_day pass
  (atom []))

(defn- migrate-kind!
  "→ {:kind … :read n :violations [{:id … :errors …} …]}; inserts only
  when apply? and the kind is violation-free."
  [eng source-db kind apply?]
  (let [rdef (get (inv/resources eng) kind)
        table (:plural rdef)
        checked
        (if (= :plan_day kind)
          ;; no v9 table: the rows fanned out of the plans' shed days
          (mapv #(assoc % :errors (violations-of rdef (:doc %)))
                @fanned-days)
          (mapv (fn [r]
                  (let [raw (wire/read-json
                             (.getValue ^org.postgresql.util.PGobject
                                        (:data r)))
                        doc (schema/decode (:schema rdef)
                                           (cond-> raw
                                             (= :plan kind) (dissoc :days)))]
                    (when (= :plan kind)
                      (let [day-rdef (get (inv/resources eng) :plan_day)]
                        (swap! fanned-days into
                               (map #(day->plan-day day-rdef (:id r)
                                                    (:owner r) %)
                                    (:days raw)))))
                    {:id (:id r)
                     :state (keyword (:state r))
                     :version (:version r)
                     :owner (:owner r)
                     :doc doc
                     :errors (violations-of rdef doc)}))
                (source-rows source-db table)))
        violations (filterv :errors checked)]
    (when (and apply? (empty? violations))
      (doseq [{:keys [id state version owner doc]} checked]
        (store/with-tx (:storage eng)
          (fn [tx]
            (let [now ((:now-fn eng))
                  row {:id id :state state :version version
                       :owner owner
                       :shape (:shape rdef 1)
                       :law-revision nil
                       :data doc}
                  row (derived/materialize rdef row now)
                  row (assoc row :summary
                             (summary/render (:summary rdef)
                                             (assoc row :kind kind)))]
              (store/insert-row!
               (:storage eng) tx kind
               (-> row
                   (update :data #(schema/encode (:schema rdef) %))
                   (dissoc :summary)))
              (store/append-transition!
               (:storage eng) tx
               {:kind kind
                :resource-id id
                :action :create
                :from-state nil
                :to-state state
                :actor {:type "system" :id "migrate-v9"
                        :display "v9 → v10 migration"}
                :law-revision nil
                :input-digest (wire/digest {})
                :correlation-id "migrate-v9"
                :summary (:summary row)}))))))
    {:kind kind :read (count checked) :violations violations}))

(defn migrate!
  [& _]
  (let [source-dsn (or (System/getenv "SOURCE_DSN")
                       (throw (ex-info "SOURCE_DSN is required (the v9 database)" {})))
        apply? (= "1" (System/getenv "APPLY"))
        source-db (jdbc/get-datasource source-dsn)
        storage (pg/storage (or (System/getenv "MEALPLAN10_DSN")
                                (throw (ex-info "MEALPLAN10_DSN is required (the v10 database)" {}))))]
    (try
      (let [eng (engine/engine {:storage storage
                                :resources (main/resources main/events)})
            ;; refuse a half-migrated or already-lived-in target: this
            ;; script is the FIRST writer or it is the wrong tool
            occupied (into []
                           (keep (fn [kind]
                                   (let [n (store/with-tx storage
                                             (fn [tx]
                                               (count (store/query-rows
                                                       storage tx kind {}
                                                       {:limit 1}))))]
                                     (when (pos? n) kind))))
                           kinds-in-order)
            _ (when (seq occupied)
                (println "REFUSED: target already holds rows for" occupied
                         "— migrate into an empty database.")
                (System/exit 1))
            reports (mapv #(migrate-kind! eng source-db % apply?)
                          kinds-in-order)
            bad (filterv (comp seq :violations) reports)]
        (doseq [{:keys [kind read violations]} reports]
          (println (format "  %-14s %4d row(s)%s"
                           (name kind) read
                           (if (seq violations)
                             (str "  ✗ " (count violations) " VIOLATION(S)")
                             ""))))
        (doseq [{:keys [kind violations]} bad
                {:keys [id errors]} violations]
          (println (str "  ✗ " (name kind) "/" id ": " (pr-str errors))))
        (cond
          (seq bad)
          (do (println "\nresidue found — nothing written. Extract or declare"
                       "these fields before migrating (the residue rule).")
              (System/exit 1))

          (not apply?)
          (do (println "\ndry run clean — APPLY=1 executes.")
              (System/exit 1))

          :else
          (do (doseq [kind kinds-in-order]
                (maintainer/backfill! eng kind :all))
              (println "\nmigrated; aggregates and clock index backfilled.")
              (let [counts (mapv (fn [{:keys [kind read]}]
                                   (let [n (store/with-tx storage
                                             (fn [tx]
                                               (count (store/query-rows
                                                       storage tx kind {}
                                                       {:limit 100000}))))]
                                     [kind read n]))
                                 reports)]
                (doseq [[kind read n] counts]
                  (println (format "  %-14s source %4d → target %4d %s"
                                   (name kind) read n
                                   (if (= read n) "✓" "✗ MISMATCH"))))
                (when (some (fn [[_ r n]] (not= r n)) counts)
                  (System/exit 1))))))
      (finally
        (pg/close! storage)
        (shutdown-agents)))))

(defn -main [& args] (apply migrate! args))
