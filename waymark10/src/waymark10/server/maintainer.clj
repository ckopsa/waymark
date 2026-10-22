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
    grammar, SUM of the target's :of column — read through
    store/sum-matching (the protocol op batch C named as follow-up,
    landed in DX round 3), so sums work over every Storage backend;
    the maintainer only lands the value in the fact's declared type.
    A sum with no contributions (zero matching rows, or matches
    whose :of are all nil) lands 0 by default; {:when-empty :absent}
    on the declaration lands nil instead — no information is not a
    zero (the unpriced-is-not-free law).
  - A spec's :flips-at fn is scheduling advice, never law: it changes
    when the index re-checks, not what any fact means, so it is not
    fingerprinted.

  Flip instants: for the comparison patterns — a clock fact whose
  (var :now)-reading comparisons are all of the shape
  (< (date-of (var :now)) (var :date_field)) (either side, any
  normalized comparison op) — the flip instant derives from the
  compared field's value: an instant is its own candidate; a date
  contributes the UTC midnights bounding it (a spurious candidate is
  swept once, silently, and advances the index). A fact that reads
  :today (the house's day) adds one shape, (< (var :today) <date>),
  where <date> is a field or a field plus or minus a constant period:
  its candidates are the midnights bounding that date IN THE GOVERNING
  CALENDAR'S ZONE (docs/spec-calendar-time.md §2). An inscrutable
  expression falls back to the spec's declared {:flips-at (fn [row]
  …)}, else to a fixed re-check interval (15 minutes)."
  (:require [clojure.string :as str]
            [waymark10.derived :as derived]
            [waymark10.expr :as expr]
            [waymark10.measures :as measures]
            [waymark10.retention :as retention]
            [waymark10.saved-view :as sv]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.events :as events]
            [waymark10.server.held-calls :as held-calls]
            [waymark10.server.invoke :as inv]
            [waymark10.server.measure :as measure]
            [waymark10.server.readings :as readings]
            [waymark10.server.store :as store]
            [waymark10.server.work-calendar :as work-calendar]
            [waymark10.summary :as summary]
            [waymark10.types :as t]
            [waymark10.windows :as windows])
  (:import (java.math BigDecimal RoundingMode)
           (java.time Duration Instant LocalDate ZoneId ZoneOffset)
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
  "The kind's clock facts — expression facts that declare :now or
  :today in :over (the v10 spelling of waymark9's Clock input). With a
  revision, the trees resolve under that law (batch C)."
  ([rmap] (clock-specs rmap nil))
  ([rmap revision]
   (into {}
         (filter (fn [[_ d]] (and (:expr d) (some #{:now :today} (:over d)))))
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
  (let [vars (:vars (expr/info form))]
    (or (contains? vars :now) (contains? vars :today))))

(defn- field-of
  "The row field `form` reads bare — never the clock or the house's day."
  [form]
  (let [f (bare-field form)]
    (when (and (keyword? f) (not (#{:now :today} f))) f)))

(defn- constant?
  "True when `form` reads nothing: no var, field, input, clock or calendar."
  [form]
  (let [i (expr/info form)]
    (and (empty? (:vars i)) (empty? (:data i)) (empty? (:inputs i))
         (not (:uses-now i)) (not (:uses-calendar i)))))

(defn- house-date
  "The date side of a (var :today) comparison when it is a field, or a
  field plus or minus a constant period → {:field kw :form form}, else nil."
  [form]
  (if-some [f (field-of form)]
    {:field f :form form}
    (when (and (seq? form) (= 3 (count form)))
      (let [[op x y] form]
        (cond
          (and (= '+ op) (field-of x) (constant? y)) {:field (field-of x) :form form}
          (and (= '+ op) (field-of y) (constant? x)) {:field (field-of y) :form form}
          (and (= '- op) (field-of x) (constant? y)) {:field (field-of x) :form form})))))

(defn- scan-clock
  "Walk a clock fact's normalized expr for the invertible comparison
  patterns → {:fields #{kw} :dates #{{:field kw :form form}}
  :inscrutable? bool}: :fields compare with the clock, :dates with the
  house's day."
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
            (and (= :now fa) (keyword? fb) (not (#{:now :today} fb)))
            (update acc :fields conj fb)
            (and (= :now fb) (keyword? fa) (not (#{:now :today} fa)))
            (update acc :fields conj fa)
            (and (= :today fa) (house-date b))
            (update acc :dates conj (house-date b))
            (and (= :today fb) (house-date a))
            (update acc :dates conj (house-date a))
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

(defn- house-flip-candidates
  "The midnights in `zone` that bound the date `form` answers over
  `data` — the moments a (var :today) comparison with it can change."
  [{:keys [field form]} data ^ZoneId zone ^Instant now]
  (let [d (expr/evaluate form {:vars {field (get data field)} :now now})]
    (if (instance? LocalDate d)
      [(.toInstant (.atStartOfDay ^LocalDate d zone))
       (.toInstant (.atStartOfDay (.plusDays ^LocalDate d 1) zone))]
      [])))

(defn next-flip-at
  "The earliest future instant at which any clock fact of this row
  can change its value — the maintained index the sweep reads. nil
  when the kind has no clock facts, or none can flip again without a
  write. data is the DECODED document; revision is the row's law
  stamp (the flip scan reads the trees the row is judged by);
  calendar is the :calendar hook, whose default calendar's zone bounds
  the house's day — without it a :today fact re-checks on the
  interval, never at UTC midnight by accident."
  ([rmap data ^Instant now] (next-flip-at rmap nil data now nil))
  ([rmap revision data ^Instant now] (next-flip-at rmap revision data now nil))
  ([rmap revision data ^Instant now calendar]
   (let [zone (when calendar
                (try (:zone (calendar nil)) (catch Exception _ nil)))
         moments
         (mapcat
          (fn [[_fact spec]]
            (if-some [fa (:flips-at spec)]
              (when-some [t (try (fa {:data data}) (catch Exception _ nil))]
                (when (.isAfter ^Instant t now) [t]))
              (let [{:keys [fields dates inscrutable?]}
                    (scan-clock (:expr spec) {:fields #{} :dates #{} :inscrutable? false})]
                (if (or inscrutable? (and (seq dates) (not (instance? ZoneId zone))))
                  [(.plusMillis now recheck-interval-ms)]
                  (into []
                        (filter #(.isAfter ^Instant % now))
                        (concat (mapcat #(flip-candidates (get data %)) fields)
                                (mapcat #(house-flip-candidates % data zone now) dates)))))))
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

;; ── the SUM read (batch C; protocol op since round 3) ───────────────

(defn- strip-zeros ^java.math.BigDecimal [^java.math.BigDecimal d]
  (let [s (.stripTrailingZeros d)]
    (if (neg? (.scale s)) (.setScale s 0) s)))

(defn- land-sum
  "store/sum-matching's exact decimal, landed in the fact's declared
  type: long for an :int fact, trailing-zero-stripped decimal
  otherwise — so a re-read never disagrees with itself by scale
  alone."
  [^java.math.BigDecimal s fact-rdef fact]
  (let [int-fact? (= :int (let [fs (schema/field-schema (:schema fact-rdef) fact)]
                            (if (vector? fs) (first fs) fs)))]
    (if int-fact?
      (long (.longValueExact (.setScale s 0)))
      (strip-zeros s))))

(defn- aggregate-value
  "One aggregate fact for one row: COUNT (or SUM of :of) over the
  edge's target, join conditions bound to this row's values,
  where-filtered. A nil join value relates to nothing — the aggregate
  is 0 (nil for an absent-over-empty sum). A sum with no
  contributions lands its declared empty value: 0 unless the spec
  says {:when-empty :absent}, which lands nil — Postgres's
  un-coalesced SUM and the memory twin both answer nil there, so the
  two storages cannot disagree. spec arrives already resolved under
  the row's law (the where-filters may be a stored revision's)."
  [eng tx rdef row fact spec]
  (let [c (or (:count spec) (:sum spec))
        of (when (:sum spec) (:of (:sum spec)))
        absent? (= :absent (get-in spec [:sum :when-empty]))
        storage (:storage eng)
        run (fn [target-kind _target-rdef conds]
              (if of
                (let [s (store/sum-matching storage tx target-kind of conds)]
                  (cond
                    (some? s) (land-sum s rdef fact)
                    absent?   nil
                    :else     (land-sum 0M rdef fact)))
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
          ;; nothing relates: zero matching rows by construction
          (if absent? nil 0)
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
  the same transaction. → {:row decoded' :changed [facts] :before
  data} or nil when the row is gone."
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
                   (:data (derived/materialize rdef (assoc row :data data) now
                                               (:calendar (inv/render-hooks eng))))
                   data)
            nf (next-flip-at rdef revision data now (:calendar (inv/render-hooks eng)))
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
         :changed changed
         :before (:data row)}))))

(defn- tell-facts!
  "After the maintenance write commits: the notice rules that hear a
  derived fact turning true (held-calls/notice-rules-facts!)."
  [eng kind {:keys [row changed before]}]
  (when (seq changed)
    (held-calls/notice-rules-facts! eng kind (:id row) before (:data row))))

(defn recompute-counts!
  "Recompute one row's aggregate facts (SQL COUNT/SUM over the edge
  targets) and, when values moved, the expression facts that read
  them — in one transaction of its own, under the row's law.
  Maintenance, not a write: version untouched, no transition logged;
  a change announces itself as a derivation observation. → {:row …
  :changed [facts]} or nil."
  [eng kind row-id]
  (let [res (store/with-tx (:storage eng)
              (fn [tx] (maintain-row!* eng tx kind row-id {})))]
    (tell-facts! eng kind res)
    res))

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

;; ── default goals (docs/spec-measures.md §3) ────────────────────────

(def goal-actor
  "The engine's hand when it writes a declared measure's first goal."
  (t/principal {:id "waymark10-goals" :type :system :display "Goals"}))

(defn- active-goal? [eng token]
  (boolean
   (some #(= "active" (name (:state %)))
         (store/with-tx (:storage eng)
           (fn [tx] (store/query-rows (:storage eng) tx :goal
                                      {:measure token} {:limit 200}))))))

(defn default-goals!
  "Write one goal row, as the engine, for every declared measure that
  carries a :default-target and has no active goal — at boot and on
  each law revision. A seed, not law: a measure with a goal is left
  alone, so a restated goal is never overwritten and a second call
  writes nothing. Nothing when the engine serves no `goal` kind.
  Answers the number of goals written."
  [eng]
  (let [resources (inv/resources eng)]
    (if-not (contains? resources :goal)
      0
      (reduce
       (fn [n [token dt]]
         (if (active-goal? eng token)
           n
           (try
             (inv/create! eng :goal
                          {:measure token :op (:op dt) :target (bigdec (:target dt))
                           :note "Written from the measure's declared :default-target."}
                          {:principal goal-actor})
             (inc n)
             (catch Exception e
               (warn! "default goal for " token " failed: " (ex-message e))
               n))))
       0
       (for [[kind rdef] (sort-by (comp str key) resources)
             :when (map? (:measures rdef))
             [mname m] (sort-by (comp str key) (:measures rdef))
             :let [dt (when (map? m) (:default-target m))]
             :when (measures/default-target? dt)]
         [(str (name kind) "." (measures/spelled mname)) dt])))))

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
         (let [outs (store/with-tx (:storage eng)
                      (fn [tx]
                        (let [due (store/due-flips (:storage eng) tx kind
                                                   ((:now-fn eng)) 200)]
                          (mapv (fn [raw]
                                  (maintain-row!* eng tx kind (:id raw)
                                                  {:reclock? true :obs-class "flip"}))
                                due))))
               n (count outs)]
           ;; told after the commit, so a slow send holds no row lock
           (doseq [o outs] (tell-facts! eng kind o))
           (if (= 200 n) (recur (+ total n)) (+ total n))))))
   0
   (inv/resources eng)))

;; ── the measure sweep (docs/spec-measures.md §4) ─────────────────────

(def ^:private default-cadence-seconds
  "How often a measure whose :tell seat names no cadence is swept."
  3600)

(defn- declared-measures
  "[token measure] for every measure a served kind declares, sorted."
  [eng]
  (for [[kind rdef] (sort-by (comp str key) (inv/resources eng))
        :when (map? (:measures rdef))
        [mname m] (sort-by (comp str key) (:measures rdef))
        :when (map? m)]
    [(str (name kind) "." (measures/spelled mname)) m]))

(defn- first-decoded
  "The first row of kind matching the equality map where, decoded; nil
  when there is none or the engine serves no such kind."
  [eng kind where]
  (when-some [rdef (rdef-of eng kind)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit 1})))
             first
             (decode-row rdef))))

(defn- cadence-seconds
  "The :tell seat's cadence_seconds; hourly when it names none."
  [eng tell]
  (let [c (when-some [seat-name (measures/spelled tell)]
            (some-> (first-decoded eng :seat {:name seat-name}) :data :cadence_seconds))]
    (if (and (number? c) (pos? c)) (long c) default-cadence-seconds)))

(defn- open-readings
  "The open measure_reading rows naming token, decoded."
  [eng token]
  (let [rdef (rdef-of eng :measure_reading)]
    (mapv #(decode-row rdef %)
          (store/with-tx (:storage eng)
            (fn [tx] (store/query-rows (:storage eng) tx :measure_reading
                                       {:measure token :state "open"} {:limit 50}))))))

(defn- decimal-of ^BigDecimal [v]
  (cond (instance? BigDecimal v) v
        (integer? v) (BigDecimal/valueOf (long v))
        (number? v) (BigDecimal/valueOf (double v))
        (string? v) (try (bigdec v) (catch Exception _ nil))))

(defn- window-conds
  "The store conds that keep a row whose :at falls in window w: by
  date for a date field, by instant otherwise."
  [trdef at w]
  (let [s (schema/field-schema (:schema trdef) at)]
    (if (= :waymark/date (if (vector? s) (first s) s))
      [{:target :data :field at :cast "date" :op :>= :value (str (:from w))}
       {:target :data :field at :cast "date" :op :< :value (str (:until w))}]
      [{:target :data :field at :cast "timestamptz" :op :>= :value (str (:start w))}
       {:target :data :field at :cast "timestamptz" :op :< :value (str (:end w))}])))

(defn- window-points
  "[placed row] per row a measure reads over window w through conds,
  with n the count read: for a field :at, each row whose :at falls in
  w, placed by it; for transition:<action>, each logged transition of
  that action in w on a row conds admit, placed by the transition's
  time — a row moved twice is read twice. :points is nil past
  measure/read-cap."
  [eng m trdef conds w]
  (let [of (:of m)
        st (:storage eng)
        action (measures/transition-action (measures/spelled (:at m)))]
    (if action
      (let [marks (store/with-tx st
                    (fn [tx]
                      (store/transition-marks st tx of (keyword action)
                                              (:start w) (:end w) conds
                                              (inc measure/read-cap))))
            n (count marks)
            ids (vec (distinct (map #(str (:resource-id %)) marks)))
            by-id (when (and (seq ids) (<= n measure/read-cap))
                    (into {}
                          (map (fn [raw]
                                 (let [row (decode-row trdef raw)] [(str (:id row)) row])))
                          (store/with-tx st
                            (fn [tx]
                              (store/search-rows st tx of [{:target :id :op :in :values ids}]
                                                 {:order-by nil :desc nil
                                                  :limit (count ids)})))))]
        {:n n
         :what "transitions"
         :points (when (<= n measure/read-cap)
                   (into [] (keep #(some->> (get by-id (str (:resource-id %)))
                                            (vector (:at %))))
                         marks))})
      (let [at (keyword (measures/spelled (:at m)))
            raws (store/with-tx st
                   (fn [tx]
                     (store/search-rows st tx of (into (vec conds) (window-conds trdef at w))
                                        {:order-by nil :desc nil
                                         :limit (inc measure/read-cap)})))
            n (count raws)]
        {:n n
         :what "rows"
         :points (when (<= n measure/read-cap)
                   (mapv #(let [row (decode-row trdef %)] [(get-in row [:data at]) row])
                         raws))}))))

(defn- read-window
  "One measure's numbers over window w, read as the engine through the
  :among filter and the window on :at — a time field, or the log for
  transition:<action> (window-points): {:among :met :unknown :value
  :evidence}. Past measure/read-cap rows or transitions it answers
  {:among :unread} and no number from part of the window. A :rate row
  whose :where is unknown is not met and is counted in :unknown;
  evidence is the ids of at most five rows that did not meet it,
  newest first."
  [eng m w calendar-of]
  (let [trdef (rdef-of eng (:of m))
        stat (measures/spelled (:stat m))
        {:keys [conds]} (collections/parse-query
                         trdef
                         (into {} (map (fn [[k v]] [(name k) v]))
                               (sv/parse-where (:among m)))
                         {:defaults? false})
        {:keys [n what points]} (window-points eng m trdef conds w)]
    (if (nil? points)
      {:among n
       :unread (str "over " measure/read-cap " " what " fall in the window: "
                      "no number is read from part of it — narrow :among")}
      (let [n (count points)
            rows (mapv second points)]
        (if (= "rate" stat)
          (let [form (expr/normalize (:where m))
                now ((:now-fn eng))
                verdicts (mapv (fn [row]
                                 [row (expr/evaluate form (measures/where-scope row now calendar-of))])
                               rows)
                verdicts (mapv (fn [[placed] [row v]] [row v placed]) points verdicts)
                met (count (filter (comp true? second) verdicts))]
            {:among n
             :met met
             :unknown (count (filter (comp expr/unknown? second) verdicts))
             :value (when (pos? n)
                      (.divide (BigDecimal/valueOf (long met)) (BigDecimal/valueOf (long n))
                               4 RoundingMode/HALF_UP))
             :evidence (->> verdicts
                            (remove (comp true? second))
                            (sort-by #(str (nth % 2)) #(compare %2 %1))
                            (map #(str (:id (first %))))
                            distinct
                            (take 5)
                            vec)})
          (let [value-of (measures/value-fn (measures/parse-field (measures/spelled (:field m))))]
            {:among n
             :value (when (pos? n) (decimal-of (measures/stat-of stat (map value-of rows))))
             :evidence []}))))))

(defn- verdict
  "met, missed or unjudged against the goal in force (measures/verdict)."
  [m r goal]
  (measures/verdict m r (get-in goal [:data :op]) (get-in goal [:data :target])))

(defn- reading-data
  "A reading's fields for window w, judged against the goal in force,
  its id, op and target copied so a restate never rewrites a verdict."
  [eng m w goal calendar-of now]
  (let [r (read-window eng m w calendar-of)]
    {:window_start (str (:from w))
     :window_end (str (:until w))
     :among (long (:among r))
     :met (:met r)
     :unknown (:unknown r)
     :value (when-not (:unread r) (:value r))
     :goal_id (some-> (:id goal) str)
     :op (get-in goal [:data :op])
     :target (decimal-of (get-in goal [:data :target]))
     :verdict (verdict m r goal)
     :evidence (if-some [why (:unread r)] [why] (:evidence r))
     :swept_at (str now)}))

(defn- write-reading!
  "Create the window's reading, or update the open one in place — a
  maintenance write: no version, no transition. Answers its id."
  [eng token existing data]
  (if (nil? existing)
    (str (:id (:row (inv/create! eng :measure_reading (assoc data :measure token)
                                 {:principal readings/reader}))))
    (let [rdef (rdef-of eng :measure_reading)
          id (str (:id existing))]
      (store/with-tx (:storage eng)
        (fn [tx]
          (store/update-data! (:storage eng) tx :measure_reading id
                              (schema/encode (:schema rdef) (merge (:data existing) data))
                              nil)))
      id)))

(defn- spoken
  "A reading's number as the notice says it: a rate as a percent."
  [m v]
  (when-some [^BigDecimal d (decimal-of v)]
    (if (= "rate" (measures/spelled (:stat m)))
      (str (.toPlainString (.stripTrailingZeros (.setScale (.movePointRight d 2) 1 RoundingMode/HALF_UP)))
           "%")
      (.toPlainString (.stripTrailingZeros d)))))

(def ^:private number-words
  ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine"
   "ten" "eleven" "twelve"])

(defn- span-words
  "A measure's :over as the notice says it: (weeks 4) → \"four weeks\",
  (weeks 1) → \"week\"; \"window\" when it names no period."
  [over]
  (if-some [{:keys [unit n]} (windows/period over)]
    (let [units (name unit)]
      (if (= 1 n)
        (subs units 0 (dec (count units)))
        (str (get number-words n n) " " units)))
    "window"))

(defn- late-name
  "A late row as the notice names it: its kind's label (the
  :label-template, or its name), else its summary line, else #<id> when
  the row cannot be read."
  [eng m id]
  (let [kind (:of m)
        rdef (when kind (rdef-of eng kind))
        row (when rdef
              (some->> (store/with-tx (:storage eng)
                         (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))
                       (decode-row rdef)
                       (#(assoc % :kind kind))))
        said #(when-not (str/blank? %) %)]
    (or (when row
          (or (some-> (or (:label-template rdef)
                          (when (contains? (set (schema/entry-keys (:schema rdef))) :name)
                            "{data.name}"))
                      (summary/render row)
                      said)
              (said (summary/line rdef row))))
        (str "#" id))))

(defn- miss-sentence
  "The miss notice (§4): the label, the number, the window — its span
  in words and its first day as a sentence says it — the goal, the
  count and up to three late rows by label."
  [eng m d]
  (let [late (take 3 (:evidence d))
        from (some-> (:window_start d) str LocalDate/parse)]
    (str (:label m) ": " (spoken m (:value d))
         " for the " (span-words (:over m))
         (when from (str " from " (retention/spoken from)))
         ", against a goal of " (if (= "<=" (str (:op d))) "≤ " "≥ ") (spoken m (:target d))
         (when (some? (:met d)) (str " (" (:met d) " of " (:among d) ")"))
         "."
         (when (seq late) (str " Late: " (str/join ", " (map #(late-name eng m %) late)) ".")))))

(defn- person-of-seat
  "The member a person seat's notice goes to: its owner's member row,
  by subject. nil for a model seat, which is woken instead."
  [eng seat]
  (when (and seat ((requiring-resolve 'waymark10.server.seats/interactive-seat?) seat))
    (when-some [owner (some-> (get-in seat [:data :owner]) str not-empty)]
      (some-> (first-decoded eng :member {:subject owner}) :id str))))

(defn- tell-miss!
  "A miss tells once per window (§4): stamp told_at, the sentence and
  whom it is for on the open reading, then move it through `missed` —
  the transition the engine's notice rule addresses to a person seat's
  person, and that wakes a model seat."
  [eng token m id d ^Instant now]
  (let [rdef (rdef-of eng :measure_reading)
        seat-name (measures/spelled (:tell m))
        seat (when seat-name (first-decoded eng :seat {:name seat-name}))
        stamp {:told_at (str now)
               :told (miss-sentence eng m d)
               :tell seat-name
               :tell_member (person-of-seat eng seat)}]
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/update-data! (:storage eng) tx :measure_reading id
                            (schema/encode (:schema rdef) (merge {:measure token} d stamp))
                            nil)))
    (inv/invoke! eng :measure_reading id :missed nil
                 {:principal readings/reader
                  :idempotency-key (str "measure_reading:" id ":missed")})))

(defn- sweep-measure!
  "One measure: seal each open reading whose window has ended, with its
  final values, then write the current window's reading when it is
  due — no open reading yet, or the last sweep is a cadence ago. The
  first time the current reading is missed it tells, once per window,
  even when it flaps back to met and missed again.
  Answers the number of readings written."
  [eng token m cal calendar-of ^Instant now]
  (let [window-of #(windows/window (:over m) cal
                                   (LocalDate/parse (str (get-in % [:data :window_start]))))
        cur (windows/window (:over m) cal now)
        open (open-readings eng token)
        ended (filterv #(when-some [w (window-of %)]
                          (not (.isBefore now ^Instant (:end w))))
                       open)
        current (first (filter #(= (str (:from cur)) (str (get-in % [:data :window_start])))
                               open))
        swept (some-> current (get-in [:data :swept_at]) str Instant/parse)
        due? (or (nil? swept)
                 (>= (.getSeconds (Duration/between swept now))
                     (cadence-seconds eng (:tell m))))
        goal (when (or (seq ended) due?)
               (first-decoded eng :goal {:measure token :state "active"}))]
    (doseq [r ended]
      (let [id (write-reading! eng token r (reading-data eng m (window-of r) goal
                                                         calendar-of now))]
        (inv/invoke! eng :measure_reading id :seal nil {:principal readings/reader})))
    (when due?
      (let [data (reading-data eng m cur goal calendar-of now)
            id (write-reading! eng token current data)]
        (when (and (= "missed" (:verdict data))
                   (nil? (get-in current [:data :told_at])))
          (tell-miss! eng token m id (merge (:data current) data) now))))
    (+ (count ended) (if due? 1 0))))

(defn sweep-measures!
  "Write one measure_reading per declared measure per window
  (docs/spec-measures.md §4): each measure at its :tell seat's cadence
  (hourly when the seat has none) and once more after its window ends,
  when the reading is sealed. Windows are aligned in the governing
  work_calendar's zone, so with no calendar nothing is swept; nothing
  either when the engine serves no measure_reading. A measure that
  counts transition:<action> reads the log's transitions in the window
  (store/transition-marks). Answers the number of readings written."
  [eng]
  (if-not (contains? (inv/resources eng) :measure_reading)
    0
    (let [calendar-of (:calendar (inv/render-hooks eng))
          cal (work-calendar/governing {:calendar calendar-of})
          now ((:now-fn eng))]
      (if-not (map? cal)
        0
        (reduce
         (fn [n [token m]]
           (try
             (+ n (sweep-measure! eng token m cal calendar-of now))
             (catch Exception e
               (warn! "measure sweep of " token " failed: " (ex-message e))
               n)))
         0
         (declared-measures eng))))))

(defn reread-sealed
  "A measure as a proposal declares it, `m`, read over the last sealed
  window of `token` — the window before the one now falls in — beside
  that window's sealed reading (docs/spec-measures.md §7, the law
  sweep): {:measure :window_start :window_end :sealed :old {:value
  :verdict} :new {:among :met :value :verdict}}, the new value judged
  against the goal the sealed reading copied, or the active goal when
  the window sealed none. nil when the engine serves no readings or has
  no governing calendar."
  [eng token m]
  (when (contains? (inv/resources eng) :measure_reading)
    (let [calendar-of (:calendar (inv/render-hooks eng))
          cal (work-calendar/governing {:calendar calendar-of})
          cur (when (map? cal) (windows/window (:over m) cal ((:now-fn eng))))]
      (when (map? cur)
        (let [w (windows/window (:over m) cal (.minusDays ^LocalDate (:from cur) 1))
              sealed (first-decoded eng :measure_reading
                                    {:measure token :window_start (str (:from w)) :state "sealed"})
              judged-by (if sealed
                          (:data sealed)
                          (:data (first-decoded eng :goal {:measure token :state "active"})))
              r (read-window eng m w calendar-of)]
          {:measure token
           :window_start (str (:from w))
           :window_end (str (:until w))
           :sealed (some-> (:id sealed) str)
           :old {:value (get-in sealed [:data :value])
                 :verdict (get-in sealed [:data :verdict])}
           :new {:among (long (:among r))
                 :met (:met r)
                 :value (when-not (:unread r) (:value r))
                 :verdict (measures/verdict m r (:op judged-by) (:target judged-by))}})))))

(defn start-sweeper!
  "The clock daemon: sweep-clocks! and sweep-measures! every interval-ms
  (default 30s) on a daemon thread, then the notice rules whose instant has come
  (held-calls/sweep-notice-instants!) and the digests of quiet hours
  that have closed (held-calls/sweep-quiet-digests!). Engine start! owns the
  lifecycle; tests call both directly."
  [eng {:keys [interval-ms] :or {interval-ms 30000}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     ;; the boot's default goals, on the elected holder
                     (try (default-goals! eng)
                          (catch Exception e
                            (warn! "default goals failed: " (ex-message e))))
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep-clocks! eng)
                              (catch Exception e
                                (warn! "clock sweep failed: " (ex-message e))))
                         (try (sweep-measures! eng)
                              (catch Exception e
                                (warn! "measure sweep failed: " (ex-message e))))
                         (held-calls/sweep-notice-instants! eng)
                         (held-calls/sweep-quiet-digests! eng)
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
  in its promote effect.

  Backfill tells nobody: no tell-facts! here, unlike
  recompute-counts! and sweep-clocks!. A fact that turns true here
  turns true because the LAW changed, not the row; the person learns
  of a law change from the change itself, and a flood of notices on
  every revision would train them to ignore notices
  (docs/spec-measures.md §7)."
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
        calendar (:calendar (inv/render-hooks eng))
        tally (atom (into {}
                          (map (fn [f] [f {:flips 0 :of 0 :sample []}]))
                          facts))
        measure-row!
        (fn [tx raw]
          (let [row (decode-row rdef raw)]
            (when (or (nil? population) (claims? population row))
              (let [cur-data (derived/compute-facts cur-specs (:data row) now calendar
                                                    (expr/state-of row))
                    prop-data (derived/compute-facts prop-specs (:data row) now calendar
                                                     (expr/state-of row))]
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
