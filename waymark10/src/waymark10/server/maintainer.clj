(ns waymark10.server.maintainer
  "The derivation maintainer (waymark9 server/derived.py, phase 6):
  one fact, one writer. materialize (waymark10.derived) computes a
  row's own expression facts inside every write's commit; the
  maintainer computes what that in-commit pass cannot — cross-row
  count facts and clock flips — and repairs stale facts after a
  promote (backfill!).

  A maintenance write is not a write: the document (and next_flip_at)
  update in place, version untouched, NO transition logged. It IS an
  event (batch C): a maintenance pass whose facts moved appends a
  derivation-class observation to waymark10_observations inside its
  own transaction (waymark10.server.events/record-observation!) —
  class \"recompute\" (count/sum recomputes and backfill repairs) or
  \"flip\" (the clock sweep) — so live consumers hear the changes no
  transition announces.

  The trigger seam is invoke.clj's after-write! (the engine's
  :maintain hook): a committed, non-replayed write on kind T
  (1) recomputes T's own count facts and clock index, and
  (2) recomputes the count facts of rows that DEPEND on T — the
  parent via the child's :via ref for owns edges, the source rows
  whose join conditions match the changed row for related edges —
  synchronously, in the same call, each row in its own transaction.
  A dependent whose facts flipped chains to ITS dependents; a
  per-write visited set terminates (cross-kind fact cycles have no
  assembly-time DAG check yet — named punt in checks-assembly).

  Recorded bounds and punts, each a sentence:
  - Fan-out is bounded: an inverted related query fetches at most
    :maintainer-fan-out (default 200) dependents; the overflow is
    dropped with a *err* warning naming the edge — correct-first,
    the backfill is the repair.
  - A moved :via ref leaves the OLD parent's count stale until that
    parent's next write or a backfill — after-write! sees no before
    image (waymark9 recomputed both parents; the before seam is
    unported).
  - The idempotency store keeps the envelope rendered INSIDE the
    write's transaction, so a stored replay's bytes predate this
    call's count maintenance — the honest replay of what the first
    execution answered.
  - The clock sweep does not chain into dependents (waymark9's tick
    had the same property); a count-where over a clock fact re-syncs
    at the target's next write or backfill.
  - Every recompute here evaluates under the ROW's law (batch C —
    the derived-law overlay, waymark10.derived/specs-under): a
    grandfathered row's expression facts, aggregate where-filters and
    clock scans resolve from its revision's stored fingerprint; edge
    identity rides resident, the judgment precedent. The reverse
    dependency map (dependent-edges) stays RESIDENT — edges are
    :code-or-shape, so no live revision's edges can differ.
  - :sum (batch C) is the count's sibling: same edges, same where
    grammar, SUM of the target's :of column. The SUM SQL renders here
    against the maintainer's own cond grammar — the store protocol is
    another batch's surface; a sum-matching protocol op is the named
    follow-up. Sums (like observations) are a Postgres surface.
  - A spec's :flips-at fn is scheduling advice, never law: it changes
    when the index re-checks, not what any fact means, so it is not
    fingerprinted.

  Flip instants: for the comparison patterns — a clock fact whose
  (var :now)-reading comparisons are all of the shape
  (< (date-of (var :now)) (var :date_field)) (either side, any
  normalized comparison op) — the flip instant derives from the
  compared field's value: an instant is its own candidate; a date
  contributes the UTC midnights bounding it (a spurious candidate is
  swept once, silently, and advances the index). An inscrutable
  expression falls back to the spec's declared {:flips-at (fn [row]
  …)}, else to a fixed re-check interval (15 minutes)."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.derived :as derived]
            [waymark10.expr :as expr]
            [waymark10.schema :as schema]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store])
  (:import (java.time Instant LocalDate ZoneOffset)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(def recheck-interval-ms
  "The inscrutable-clock fallback: with no derivable flip instant and
  no declared :flips-at, the row re-checks this often."
  (* 15 60 1000))

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 maintainer: " parts))))

(defn- rdef-of [eng kind] (get (inv/resources eng) kind))

(defn- decode-row [rdef row]
  ;; inv/decode-row: coercion AND the shape fold (phase 8 upcasts) —
  ;; which is why upcasts must be idempotent: this path's maintenance
  ;; write persists upcast data without the shape stamp
  (inv/decode-row rdef row))

;; ── clock facts and the flip instant ────────────────────────────────

(defn- clock-specs
  "The kind's clock facts — expression facts that declare :now in
  :over (the v10 spelling of waymark9's Clock input). With a
  revision, the trees resolve under that law (batch C)."
  ([rmap] (clock-specs rmap nil))
  ([rmap revision]
   (into {}
         (filter (fn [[_ d]] (and (:expr d) (some #{:now} (:over d)))))
         (derived/specs-under rmap revision))))

(def ^:private comparison-ops '#{< <= = not=})

(defn- bare-field
  "(var :f) → :f, (date-of (var :f)) → :f, else nil."
  [form]
  (when (seq? form)
    (cond
      (= 'var (first form)) (second form)
      (= 'date-of (first form)) (bare-field (second form)))))

(defn- now-reader? [form]
  (boolean (contains? (:vars (expr/info form)) :now)))

(defn- scan-clock
  "Walk a clock fact's normalized expr for the invertible comparison
  pattern → {:fields #{kw} :inscrutable? bool}."
  [form acc]
  (if-not (seq? form)
    acc
    (let [[op & args] form]
      (cond
        (contains? comparison-ops op)
        (let [[a b] args
              fa (bare-field a)
              fb (bare-field b)]
          (cond
            (and (= :now fa) (keyword? fb) (not= :now fb))
            (update acc :fields conj fb)
            (and (= :now fb) (keyword? fa) (not= :now fa))
            (update acc :fields conj fa)
            (or (now-reader? a) (now-reader? b))
            (assoc acc :inscrutable? true)
            :else acc))

        (contains? '#{and or not} op)
        (reduce #(scan-clock %2 %1) acc args)

        (now-reader? form)
        (assoc acc :inscrutable? true)

        :else acc))))

(defn- flip-candidates
  "The moments a now-vs-value comparison can change truth."
  [v]
  (cond
    (instance? Instant v) [v]
    (instance? LocalDate v)
    (let [midnight (.toInstant (.atStartOfDay ^LocalDate v ZoneOffset/UTC))]
      [midnight (.plusSeconds midnight 86400)])
    :else []))

(defn next-flip-at
  "The earliest future instant at which any clock fact of this row
  can change its value — the maintained index the sweep reads. nil
  when the kind has no clock facts, or none can flip again without a
  write. data is the DECODED document; revision is the row's law
  stamp (the flip scan reads the trees the row is judged by)."
  ([rmap data ^Instant now] (next-flip-at rmap nil data now))
  ([rmap revision data ^Instant now]
   (let [moments
         (mapcat
          (fn [[_fact spec]]
            (if-some [fa (:flips-at spec)]
              (when-some [t (try (fa {:data data}) (catch Exception _ nil))]
                (when (.isAfter ^Instant t now) [t]))
              (let [{:keys [fields inscrutable?]}
                    (scan-clock (:expr spec) {:fields #{} :inscrutable? false})]
                (if inscrutable?
                  [(.plusMillis now recheck-interval-ms)]
                  (into []
                        (comp (mapcat #(flip-candidates (get data %)))
                              (filter #(.isAfter ^Instant % now)))
                        fields)))))
          (clock-specs rmap revision))]
     (when (seq moments)
       (reduce #(if (.isBefore ^Instant %2 ^Instant %1) %2 %1) moments)))))

;; ── the count read ──────────────────────────────────────────────────

(defn- sql-cast
  "The target field's SQL comparison type, from its schema head."
  [rdef field]
  (let [s (schema/field-schema (:schema rdef) field)
        head (if (vector? s) (first s) s)]
    (case head
      :waymark/date "date"
      :waymark/instant "timestamptz"
      :boolean "boolean"
      :int "bigint"
      (:double :decimal) "numeric"
      "text")))

(defn- where-conds [target-rdef where]
  (mapv (fn [[f vs]]
          (let [vals (mapv #(if (keyword? %) (name %) (str %))
                           (sort-by str vs))]
            (if (= :state f)
              {:target :state :op :in :values vals}
              {:target :data :field (name f) :cast (sql-cast target-rdef f)
               :op :in :values vals})))
        where))

(defn- owns-edge [rdef child-kind]
  (some #(when (= child-kind (:kind %)) %) (:owns rdef)))

(def ^:private flip-op {:= := :< :> :<= :>= :>= :<= :> :<})

;; ── the SUM read (batch C) ──────────────────────────────────────────
;; The maintainer's local twin of the storage cond renderer, for the
;; one aggregate the store protocol does not yet speak — the store
;; surface is another batch's file; a sum-matching protocol op is the
;; named follow-up. Identifiers come from checked declarations, casts
;; from the same closed set.

(def ^:private safe-casts #{"date" "boolean" "bigint" "numeric" "text"
                            "timestamptz"})
(def ^:private cond-ops {:= "=" :< "<" :<= "<=" :>= ">=" :> ">"})

(defn- cond-frag [{:keys [target field cast op value values]}]
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
       [value]])))

(defn- strip-zeros ^java.math.BigDecimal [^java.math.BigDecimal d]
  (let [s (.stripTrailingZeros d)]
    (if (neg? (.scale s)) (.setScale s 0) s)))

(defn- sum-matching
  "SUM of the target's :of column over the cond-matched rows; the
  empty set sums to 0. The value lands in the fact's declared type:
  long for an :int fact, trailing-zero-stripped decimal otherwise —
  so a re-read never disagrees with itself by scale alone."
  [tx target-rdef fact-rdef fact of conds]
  (let [table (:table (store/kind-projection target-rdef))
        fname (store/definition-checked-name of)
        parts (map cond-frag conds)
        sql (str "SELECT COALESCE(SUM((data->>'" fname "')::numeric), 0) AS s"
                 " FROM " table
                 (when (seq parts)
                   (str " WHERE " (str/join " AND " (map first parts)))))
        s ^java.math.BigDecimal
        (:s (jdbc/execute-one! tx (into [sql] (mapcat second parts))
                               {:builder-fn rs/as-unqualified-lower-maps}))
        int-fact? (= :int (let [fs (schema/field-schema (:schema fact-rdef) fact)]
                            (if (vector? fs) (first fs) fs)))]
    (if int-fact?
      (long (.longValueExact (.setScale ^java.math.BigDecimal s 0)))
      (strip-zeros s))))

(defn- aggregate-value
  "One aggregate fact for one row: COUNT (or SUM of :of) over the
  edge's target, join conditions bound to this row's values,
  where-filtered. A nil join value relates to nothing — the aggregate
  is 0. spec arrives already resolved under the row's law (the
  where-filters may be a stored revision's)."
  [eng tx rdef row fact spec]
  (let [c (or (:count spec) (:sum spec))
        of (when (:sum spec) (:of (:sum spec)))
        storage (:storage eng)
        run (fn [target-kind target-rdef conds]
              (if of
                (sum-matching tx target-rdef rdef fact of conds)
                (store/count-matching storage tx target-kind conds)))]
    (if-some [child-kind (:owns c)]
      (let [edge (owns-edge rdef child-kind)
            child-rdef (rdef-of eng child-kind)
            conds (into [{:target :data :field (name (:via edge)) :cast "text"
                          :op := :value (:id row)}]
                        (where-conds child-rdef (:where c)))]
        (run child-kind child-rdef conds))
      (let [edge (get (:related rdef) (:related c))
            target-rdef (rdef-of eng (:kind edge))
            joins (map (fn [[ours op theirs]]
                         [(get-in row [:data ours]) op theirs])
                       (:on edge))]
        (if (some (comp nil? first) joins)
          0
          (let [conds (into (mapv (fn [[v op theirs]]
                                    ;; ours op theirs, ours bound to v
                                    ;; ⇒ theirs (flip op) v
                                    (if (= :id theirs)
                                      {:target :id :op (flip-op op)
                                       :value (str v)}
                                      {:target :data :field (name theirs)
                                       :cast (sql-cast target-rdef theirs)
                                       :op (flip-op op) :value (str v)}))
                                  joins)
                            (where-conds target-rdef (:where c)))]
            (run (:kind edge) target-rdef conds)))))))

;; ── the maintenance pass ────────────────────────────────────────────

(defn- maintain-row!*
  "tx-scoped: recompute the row's aggregate facts (and, when they
  moved or reclock? is set, its expression facts) plus the clock
  index — every spec resolved under the ROW's law (batch C); write
  only when something changed, and announce what changed as a
  derivation-class observation (:obs-class, default \"recompute\") in
  the same transaction. → {:row decoded' :changed [facts]} or nil
  when the row is gone."
  [eng tx kind id {:keys [reclock? obs-class]}]
  (let [storage (:storage eng)
        rdef (rdef-of eng kind)]
    (when-some [raw (store/load-row storage tx kind id {:for-update true})]
      (let [row (decode-row rdef raw)
            revision (:law-revision row)
            now ((:now-fn eng))
            aggs (into {}
                       (map (fn [[fact spec]]
                              [fact (aggregate-value eng tx rdef row fact spec)]))
                       (derived/aggregate-specs rdef revision))
            aggs-moved? (not= aggs (select-keys (:data row) (keys aggs)))
            data (merge (:data row) aggs)
            data (if (or reclock? aggs-moved?)
                   (:data (derived/materialize rdef (assoc row :data data) now))
                   data)
            nf (next-flip-at rdef revision data now)
            changed (into []
                          (filter #(not= (get data %) (get-in row [:data %])))
                          (sort (keys (:derived rdef))))]
        (when (or (seq changed) (not= nf (:next-flip-at row)))
          (store/update-data! storage tx kind id
                              (schema/encode (:schema rdef) data) nf))
        (when (seq changed)
          (events/record-observation! storage tx
                                      {:kind kind :resource-id id
                                       :class (or obs-class "recompute")
                                       :changed (mapv name changed)}))
        {:row (assoc row :data data :next-flip-at nf)
         :changed changed}))))

(defn recompute-counts!
  "Recompute one row's aggregate facts (SQL COUNT/SUM over the edge
  targets) and, when values moved, the expression facts that read
  them — in one transaction of its own, under the row's law.
  Maintenance, not a write: version untouched, no transition logged;
  a change announces itself as a derivation observation. → {:row …
  :changed [facts]} or nil."
  [eng kind row-id]
  (store/with-tx (:storage eng)
    (fn [tx] (maintain-row!* eng tx kind row-id {}))))

;; ── the invalidation map's other direction ──────────────────────────

(defn- dependent-edges
  "Which kinds' aggregate facts read t-kind, and through what:
  [{:kind R :via kw} (owns) | {:kind R :on [[ours op theirs]…]}].
  Built from RESIDENT declarations — edges are :code-or-shape, so no
  live revision's edges can differ (the recorded overlay boundary)."
  [eng t-kind]
  (distinct
   (for [[rk rdef] (inv/resources eng)
         [_ spec] (derived/aggregate-specs rdef)
         :let [c (or (:count spec) (:sum spec))
               entry (cond
                       (= t-kind (:owns c))
                       {:kind rk :via (:via (owns-edge rdef (:owns c)))}
                       (and (:related c)
                            (= t-kind (get-in rdef [:related (:related c) :kind])))
                       {:kind rk :on (get-in rdef [:related (:related c) :on])})]
         :when entry]
     entry)))

(defn- dependent-ids
  "The rows one changed row dirties through one edge — the FK
  dereference for owns, the inverted predicate for related. Bounded:
  at most :maintainer-fan-out ids; overflow warns and drops."
  [eng entry t-kind row]
  (if-some [via (:via entry)]
    (when-some [pid (get-in row [:data via])] [(str pid)])
    (let [src-rdef (rdef-of eng (:kind entry))
          binds (map (fn [[ours op theirs]]
                       [ours op (if (= :id theirs)
                                  (:id row)
                                  (get-in row [:data theirs]))])
                     (:on entry))]
      (when (every? (comp some? last) binds)
        (let [conds (mapv (fn [[ours op v]]
                            ;; ours op theirs with theirs bound to v —
                            ;; the condition sits on OUR column as-is
                            {:target :data :field (name ours)
                             :cast (sql-cast src-rdef ours)
                             :op op :value (str v)})
                          binds)
              limit (:maintainer-fan-out eng 200)
              ids (store/with-tx (:storage eng)
                    (fn [tx]
                      (store/ids-matching (:storage eng) tx (:kind entry)
                                          conds (inc limit))))]
          (if (> (count ids) limit)
            (do (warn! "fan-out over " limit " " (name (:kind entry))
                       " dependents of one " (name t-kind) " write; "
                       "dropping the overflow (id " (last ids) " and beyond) — "
                       "backfill! is the repair")
                (vec (take limit ids)))
            ids))))))

(defn- recompute-dependents!
  [eng kind row visited]
  (doseq [entry (dependent-edges eng kind)
          id (dependent-ids eng entry kind row)
          :let [vkey [(:kind entry) id]]
          :when (not (contains? @visited vkey))]
    (swap! visited conj vkey)
    (when-some [{row' :row changed :changed}
                (recompute-counts! eng (:kind entry) id)]
      (when (seq changed)
        ;; chaining: a flipped fact is itself a change someone may count
        (recompute-dependents! eng (:kind entry) row' visited)))))

;; ── the trigger seam (invoke.clj after-write!'s :maintain hook) ─────

(defn after-write
  "The engine's :maintain hook: after a committed, non-replayed write
  on kind, recompute the written row's own count facts and clock
  index, then the count facts of every row that depends on the kind —
  same call, own transactions. Returns res, its :row refreshed when
  the row's own facts moved (the response tells the maintained
  truth)."
  [eng kind _action res]
  (if-some [row (:row res)]
    (let [rdef (rdef-of eng kind)
          own (when (or (seq (derived/aggregate-specs rdef))
                        (seq (clock-specs rdef)))
                (recompute-counts! eng kind (:id row)))
          row' (or (:row own) row)]
      (recompute-dependents! eng kind row' (atom #{[kind (:id row)]}))
      (cond-> res own (assoc :row (:row own))))
    res))

;; ── the clock sweep ─────────────────────────────────────────────────

(defn sweep-clocks!
  "Flip the facts whose time has come: for every clocked kind, sweep
  next_flip_at <= now (the engine's :now-fn), recompute the due rows'
  facts, advance (or park) the index. Maintenance — no versions, no
  transitions. Returns the number of rows recomputed."
  [eng]
  (reduce
   (fn [total [kind rdef]]
     (if (empty? (clock-specs rdef))
       total
       (loop [total total]
         (let [n (store/with-tx (:storage eng)
                   (fn [tx]
                     (let [due (store/due-flips (:storage eng) tx kind
                                                ((:now-fn eng)) 200)]
                       (doseq [raw due]
                         (maintain-row!* eng tx kind (:id raw)
                                         {:reclock? true :obs-class "flip"}))
                       (count due))))]
           (if (= 200 n) (recur (+ total n)) (+ total n))))))
   0
   (inv/resources eng)))

(defn start-sweeper!
  "The clock daemon: sweep-clocks! every interval-ms (default 30s) on
  a daemon thread. Engine start! owns the lifecycle; tests call
  sweep-clocks! directly."
  [eng {:keys [interval-ms] :or {interval-ms 30000}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep-clocks! eng)
                              (catch Exception e
                                (warn! "clock sweep failed: " (ex-message e))))
                         (recur))))
                   "waymark10-clock-sweeper")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)

;; ── backfill ────────────────────────────────────────────────────────

(defn backfill!
  "Recompute the named derived facts across every row of the kind, in
  id-keyset batches — the stale-facts repair after a promote. The
  facts argument documents intent; the recompute is wholesale (one
  fact, one definition: recomputing every fact of a row lands the
  same values). Every row recomputes under ITS OWN law (the overlay),
  so a grandfathered survivor repairs under its birth law while
  adopted rows land the new one. Returns the number of rows
  recomputed.

  The phase-6 named seam is WIRED (batch C): the definitions
  lifecycle calls (backfill! eng kind (fingerprint/stale-facts diff))
  in its promote effect."
  [eng kind _facts & {:keys [batch] :or {batch 500}}]
  (loop [after "" total 0]
    (let [ids (store/with-tx (:storage eng)
                (fn [tx]
                  (store/ids-matching (:storage eng) tx kind
                                      [{:target :id :op :> :value after}]
                                      batch)))]
      (doseq [id ids]
        (store/with-tx (:storage eng)
          (fn [tx] (maintain-row!* eng tx kind id {:reclock? true}))))
      (if (< (count ids) batch)
        (+ total (count ids))
        (recur (last ids) (+ total (count ids)))))))

;; ── the blast-radius meter (batch C, waymark9's BlastRadiusMeter) ───

(def sample-cap
  "Ids per measured fact's sample (waymark9 design §2's SAMPLE_CAP)."
  20)

(defn- claims?
  "Does a pilot population's where= equality map claim this decoded
  row? String-fallback comparison — the restamp discipline."
  [where row]
  (every? (fn [[f expected]]
            (let [v (if (= :state f)
                      (name (:state row))
                      (get-in row [:data f]))]
              (or (= v expected) (= (str v) (str expected)))))
          where))

(defn blast-radius
  "§2's measurer, synchronous (v10 has no meter job — recorded): for
  each redefined derived fact, a full id-keyset scan of the target
  kind's rows (the declared population's rows when piloted),
  evaluating the fact under BOTH laws' specs over current data and
  counting the rows whose value differs. Expression facts evaluate
  through compute-facts; aggregate facts run both where-filters'
  SQL. No silent sampling: the sample is capped, the scan is not,
  and the report says so.

  opts: :facts [fact-name-strings], :current-fp / :proposed-fp (the
  two revisions' stored fingerprints, string-keyed), :population
  (the pilot's where= map or nil).
  → {:facts [{:fact \"kind.fact\" :flips n :of total :sample […]}]
     :scan \"full\" :population …}"
  [eng kind {:keys [facts current-fp proposed-fp population]}]
  (let [rdef (rdef-of eng kind)
        cur-specs (derived/specs-from rdef current-fp)
        prop-specs (derived/specs-from rdef proposed-fp)
        facts (into []
                    (comp (map keyword)
                          (filter #(contains? (:derived rdef) %)))
                    facts)
        now ((:now-fn eng))
        tally (atom (into {}
                          (map (fn [f] [f {:flips 0 :of 0 :sample []}]))
                          facts))
        measure-row!
        (fn [tx raw]
          (let [row (decode-row rdef raw)]
            (when (or (nil? population) (claims? population row))
              (let [cur-data (derived/compute-facts cur-specs (:data row) now)
                    prop-data (derived/compute-facts prop-specs (:data row) now)]
                (doseq [fact facts]
                  (let [spec (get (:derived rdef) fact)
                        aggregate? (or (:count spec) (:sum spec))
                        [cur prop]
                        (if aggregate?
                          [(aggregate-value eng tx rdef row fact
                                            (get cur-specs fact))
                           (aggregate-value eng tx rdef row fact
                                            (get prop-specs fact))]
                          [(get cur-data fact) (get prop-data fact)])]
                    (swap! tally update fact
                           (fn [t]
                             (cond-> (update t :of inc)
                               (not= cur prop)
                               (-> (update :flips inc)
                                   (update :sample
                                           #(if (< (count %) sample-cap)
                                              (conj % (:id row))
                                              %))))))))))))]
    (loop [after ""]
      (let [ids (store/with-tx (:storage eng)
                  (fn [tx]
                    (store/ids-matching (:storage eng) tx kind
                                        [{:target :id :op :> :value after}]
                                        200)))]
        (store/with-tx (:storage eng)
          (fn [tx]
            (doseq [id ids]
              (when-some [raw (store/load-row (:storage eng) tx kind id {})]
                (measure-row! tx raw)))))
        (when (= 200 (count ids))
          (recur (last ids)))))
    {:facts (mapv (fn [fact]
                    (let [{:keys [flips of sample]} (get @tally fact)]
                      {:fact (str (name kind) "." (name fact))
                       :flips flips :of of :sample sample}))
                  facts)
     :scan "full"
     :population population}))
