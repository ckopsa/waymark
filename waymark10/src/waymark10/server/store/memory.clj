(ns waymark10.server.store.memory
  "The in-memory Storage twin (batch F): the full Storage protocol
  over one atom — kind tables, the transition log with assigned ids,
  idempotency, drafts, cursors, job leases — faithful to the Postgres
  MEANINGS so an engine built over it runs the same invoke algorithm
  and answers the same envelopes. Its uses: tests that want engine
  semantics without a database, and a REPL that wants a whole engine
  in one form.

  Fidelity decisions, each a sentence:
  - Documents ROUND-TRIP THROUGH WIRE JSON on every write
    (write-json → read-json), so what a caller reads back is exactly
    what Postgres's JSONB would have handed it — keyword keys, exact
    decimals, keywords collapsed to strings — never an in-heap value
    JSONB could not have stored.
  - The COND GRAMMAR interprets casts as value coercions: both sides
    of a Postgres cond cross as strings and cast server-side, so the
    twin parses both sides to the cast's type (date → LocalDate,
    timestamptz → Instant, bigint → long, numeric → BigDecimal,
    boolean, text) and compares with compare — numeric 1.0 = 1 holds,
    exactly as SQL numeric does; an unparseable value throws, exactly
    as the SQL cast would.
  - A nil left value fails every comparison (SQL NULL semantics);
    :in-any matches when any given token is a string element of the
    field's array (jsonb ?|'s reading — non-string elements never
    match a text token).
  - TRANSACTIONS are a global monitor plus snapshot-rollback: with-tx*
    serializes every transaction (the row lock's serialization,
    coarsened to the whole store) and an exception restores the
    pre-transaction snapshot, so an atomic bulk/batch refusal rolls
    everything back exactly as Postgres does. Reentrant: a nested
    with-tx (the maintainer's own transactions) snapshots and rolls
    back independently, like the separate connections they are on
    Postgres.
  - NOTIFY is a seam, not a LISTEN wire: append-transition! queues the
    id and the OUTERMOST successful with-tx* flushes it to every
    registered on-notify callback (subscribe-notify!) — in-process
    only, fired at commit like pg_notify, and a rolled-back write
    never notifies. The events dispatcher's LISTEN connection does not
    exist here; an engine over this storage runs without a dispatcher
    (or polls the log), recorded.
  - search-rows orders by the field's JSON value with numbers
    compared numerically (the generated column's typed ordering),
    ASC nulls-last / DESC nulls-first (Postgres's defaults), id
    tiebreak always; ids-matching orders ids as text, as the id
    column does."
  (:require [waymark10.server.store :as store]
            [waymark10.wire :as wire])
  (:import (java.math BigDecimal)
           (java.time Instant LocalDate OffsetDateTime)))

(set! *warn-on-reflection* true)

;; ── values ──────────────────────────────────────────────────────────

(defn- jsonish
  "The JSONB round-trip: what Postgres would store and hand back."
  [v]
  (some-> v wire/write-json wire/read-json))

(defn- json-text
  "data->>'f': the field's value as Postgres text, nil stays nil."
  [v]
  (cond
    (nil? v) nil
    (string? v) v
    (keyword? v) (name v)
    (instance? BigDecimal v) (.toPlainString ^BigDecimal v)
    (coll? v) (wire/write-json v)
    :else (str v)))

(defn- parse-instant ^Instant [^String s]
  (try (Instant/parse s)
       (catch Exception _
         (.toInstant (OffsetDateTime/parse s)))))

(defn- coerce
  "One side of a cond through its cast — the value coercion reading of
  Postgres's server-side cast; an unparseable value throws, as the
  cast would."
  [cast ^String s]
  (case (or cast "text")
    "date" (LocalDate/parse s)
    "timestamptz" (parse-instant s)
    "bigint" (Long/parseLong s)
    "numeric" (BigDecimal. s)
    "boolean" (case s "true" true "false" false
                    (throw (ex-info (str "invalid boolean " (pr-str s)) {})))
    s))

(defn- cmp ^long [a b]
  (compare a b))

(defn- cond-matches?
  "One cond against one stored row — the grammar's Postgres meaning."
  [row {:keys [target field cast op value values]}]
  (if (= :in-any op)
    (let [arr (get-in row [:data (keyword field)])]
      (boolean (and (sequential? arr)
                    (some (set values) (filter string? arr)))))
    (let [text (case target
                 :state (name (:state row))
                 :id (:id row)
                 (json-text (get-in row [:data (keyword field)])))]
      ;; SQL NULL: a nil left side fails every comparison
      (when (some? text)
        (let [cast (if (contains? #{:state :id} target) "text" cast)
              lval (coerce cast text)]
          (if (= :in op)
            (boolean (some #(zero? (cmp lval (coerce cast (str %)))) values))
            (let [c (cmp lval (coerce cast (str value)))]
              (case op
                := (zero? c) :< (neg? c) :<= (<= c 0)
                :>= (>= c 0) :> (pos? c)
                (throw (ex-info (str "unknown cond op " op) {:op op}))))))))))

(defn- matches-all? [row conds]
  (every? #(cond-matches? row %) conds))

(defn- sort-value
  "The ordering key one row contributes for a field — numbers compare
  numerically (the generated column's type), everything else as text."
  [v]
  (cond
    (nil? v) nil
    (number? v) (bigdec v)
    :else (json-text v)))

(defn- order-rows
  "search-rows's ordering: the promoted field's value (or state, or
  created_at), ASC nulls last / DESC nulls first, id tiebreak."
  [rows order-by desc]
  (let [keyfn (case order-by
                nil #(:created-at %)
                :state #(name (:state %))
                #(sort-value (get-in % [:data order-by])))
        cmp2 (fn [a b]
               (let [ka (keyfn a) kb (keyfn b)
                     c (cond
                         (= ka kb) 0
                         ;; nulls last ASC / first DESC = "nil is largest"
                         (nil? ka) 1
                         (nil? kb) -1
                         :else (compare ka kb))
                     c (if desc (- c) c)]
                 (if (zero? c) (compare (:id a) (:id b)) c)))]
    (sort cmp2 rows)))

;; ── the storage ─────────────────────────────────────────────────────

(defrecord MemoryStorage [state lock depth listeners]
  store/Storage

  (with-tx* [this f]
    (locking lock
      (let [snapshot @state]
        (swap! depth inc)
        (try
          (let [out (f this)]
            (when (zero? (swap! depth dec))
              ;; the outermost commit flushes the notify queue — an
              ;; event exists iff its transaction completed
              (let [pending (:pending-notify @state)]
                (when (seq pending)
                  (swap! state assoc :pending-notify [])
                  (doseq [id pending, cb @listeners]
                    (try (cb id) (catch Exception _ nil))))))
            out)
          (catch Throwable e
            (swap! depth dec)
            (reset! state snapshot)
            (throw e))))))

  (ensure-kind! [_ rmap]
    (swap! state
           (fn [s]
             (-> s
                 (assoc-in [:kinds (:kind rmap)]
                           (store/definition-checked-name (:plural rmap)))
                 (update-in [:tables (:kind rmap)] #(or % (sorted-map))))))
    nil)

  (load-row [_ _tx kind id _opts]
    (get-in @state [:tables kind id]))

  (insert-row! [_ _tx kind row]
    (let [now (Instant/now)
          stored {:id (:id row)
                  :state (keyword (:state row))
                  :version (:version row 1)
                  :data (jsonish (:data row))
                  :shape (:shape row 1)
                  :owner (:owner row)
                  :law-revision (:law-revision row)
                  :next-flip-at (:next-flip-at row)
                  :created-at now
                  :updated-at now}]
      (when (get-in @state [:tables kind (:id row)])
        (throw (ex-info (str "duplicate id " (:id row)) {:kind kind})))
      (swap! state assoc-in [:tables kind (:id row)] stored)
      row))

  (save-row! [_ _tx kind row expected-version]
    (let [current (get-in @state [:tables kind (:id row)])]
      (when (or (nil? current) (not= (:version current) expected-version))
        (throw (store/version-conflict kind (:id row) expected-version)))
      (let [now (Instant/now)
            stored (merge current
                          {:state (keyword (:state row))
                           :version (:version row)
                           :data (jsonish (:data row))
                           :shape (:shape row 1)
                           :owner (:owner row)
                           :law-revision (:law-revision row)
                           :next-flip-at (:next-flip-at row)
                           :updated-at now})]
        (swap! state assoc-in [:tables kind (:id row)] stored)
        (assoc row :updated-at now))))

  (query-rows [_ _tx kind where opts]
    (let [rows (vals (get-in @state [:tables kind]))
          match? (fn [row]
                   (every? (fn [[f v]]
                             (if (= :state f)
                               (= (name (:state row))
                                  (if (keyword? v) (name v) (str v)))
                               (= (json-text (get-in row [:data f])) (str v))))
                           where))
          keyfn (case (some-> (:order-by opts) name)
                  nil :created-at
                  "created_at" :created-at
                  "updated_at" :updated-at
                  "id" :id
                  (fn [row] (json-text (get-in row [:data (:order-by opts)]))))]
      (into []
            (take (:limit opts 100))
            (sort-by keyfn #(compare %1 %2) (filter match? rows)))))

  (append-transition! [_ _tx record]
    (let [id (:next-transition-id (swap! state update :next-transition-id
                                         (fnil inc 0)))
          rec (-> record
                  (assoc :id id :at (Instant/now))
                  (update :actor jsonish)
                  (update :inputs jsonish)
                  (update :acknowledged jsonish))]
      (swap! state #(-> %
                        (update :transitions (fnil conj []) rec)
                        (update :pending-notify (fnil conj []) id)))
      rec))

  (transitions [_ _tx where opts]
    (let [rows (cond->> (:transitions @state [])
                 (:kind where) (filter #(= (:kind where) (:kind %)))
                 (:resource-id where) (filter #(= (:resource-id where)
                                                  (:resource-id %)))
                 (:since where) (filter #(> (:id %) (:since where))))
          rows (if (:newest-first opts) (reverse rows) rows)]
      (into [] (take (:limit opts 500)) rows)))

  (idempotency-lookup [_ _tx key kind]
    (get-in @state [:idempotency [key kind]]))

  (idempotency-store! [_ _tx key kind action digest status response media-type]
    ;; ON CONFLICT DO NOTHING: the first record wins
    (swap! state update-in [:idempotency [key kind]]
           #(or % {:status status :response response :media-type media-type
                   :request-digest digest :action action}))
    nil)

  (law-count [_ _tx kind revision]
    (count (filter #(= revision (:law-revision %))
                   (vals (get-in @state [:tables kind])))))

  (restamp-law! [_ _tx kind where to-revision]
    (let [match? (fn [row]
                   (every? (fn [[f v]]
                             (case f
                               :state (= (:state row) (keyword v))
                               :law-revision (= (:law-revision row) v)
                               (= (json-text (get-in row [:data f])) (str v))))
                           where))
          ids (into [] (comp (filter match?) (map :id))
                    (vals (get-in @state [:tables kind])))]
      (doseq [id ids]
        (swap! state update-in [:tables kind id]
               #(assoc % :law-revision to-revision :updated-at (Instant/now))))
      (count ids)))

  ;; ── phase 6 ─────────────────────────────────────────────────────────

  (count-matching [_ _tx kind conds]
    (count (filter #(matches-all? % conds)
                   (vals (get-in @state [:tables kind])))))

  (ids-matching [_ _tx kind conds limit]
    (into []
          (take limit)
          (sort (map :id (filter #(matches-all? % conds)
                                 (vals (get-in @state [:tables kind])))))))

  (update-data! [_ _tx kind id data next-flip-at]
    (swap! state update-in [:tables kind id]
           #(when % (assoc % :data (jsonish data)
                           :next-flip-at next-flip-at
                           :updated-at (Instant/now))))
    nil)

  (due-flips [_ _tx kind now limit]
    (into []
          (take limit)
          (sort-by :next-flip-at
                   (filter #(and (some? (:next-flip-at %))
                                 (not (.isAfter ^Instant (:next-flip-at %)
                                                ^Instant now)))
                           (vals (get-in @state [:tables kind]))))))

  ;; ── phase 7 ─────────────────────────────────────────────────────────

  (search-rows [_ _tx kind conds {:keys [order-by desc limit offset]}]
    (let [rows (filter #(matches-all? % conds)
                       (vals (get-in @state [:tables kind])))]
      (into []
            (comp (drop (long (or offset 0)))
                  (take (long (or limit 100))))
            (order-rows rows order-by desc))))

  (facet-counts [_ _tx kind field conds array?]
    (let [rows (filter #(matches-all? % conds)
                       (vals (get-in @state [:tables kind])))
          vals* (if (= :state field)
                  (map #(name (:state %)) rows)
                  (mapcat (fn [row]
                            (let [v (get-in row [:data field])]
                              (if array?
                                (map json-text (when (sequential? v) v))
                                [(json-text v)])))
                          rows))]
      (into (sorted-map) (frequencies (remove nil? vals*)))))

  (load-draft [_ _tx kind id action audience]
    (get-in @state [:drafts [kind id (name action) audience]]))

  (save-draft! [_ _tx kind id action audience values base-version]
    (swap! state assoc-in [:drafts [kind id (name action) audience]]
           {:values (jsonish values)
            :base-version base-version
            :updated-at (Instant/now)})
    nil)

  (delete-draft! [_ _tx kind id action audience]
    (swap! state update :drafts dissoc [kind id (name action) audience])
    nil)

  ;; ── phase 9b ────────────────────────────────────────────────────────

  (cursor-get [_ _tx consumer]
    (get-in @state [:cursors consumer]))

  (cursor-set! [_ _tx consumer position]
    (swap! state assoc-in [:cursors consumer] (long position))
    nil)

  (claim-job-lease! [_ _tx job-id holder ttl-seconds]
    (let [now (Instant/now)
          lease (get-in @state [:leases job-id])]
      (if (or (nil? lease)
              (= holder (:holder lease))
              (not (.isAfter ^Instant (:expires-at lease) now)))
        (do (swap! state assoc-in [:leases job-id]
                   {:holder holder
                    :expires-at (.plusMillis now (long (* 1000 ttl-seconds)))})
            true)
        false)))

  (release-job-lease! [_ _tx job-id holder]
    (swap! state update :leases
           (fn [ls]
             (if (= holder (:holder (get ls job-id)))
               (dissoc ls job-id)
               ls)))
    nil)

  (job-lease [_ _tx job-id]
    (get-in @state [:leases job-id])))

(defn subscribe-notify!
  "Register an in-process callback for committed transition ids — the
  twin's pg_notify seam. Fired after the outermost with-tx* completes,
  one call per appended transition, in id order."
  [^MemoryStorage st f]
  (swap! (:listeners st) conj f)
  nil)

(defn storage
  "A fresh in-memory storage — the full Storage protocol over atoms.
  Build an engine over it with waymark10.server.invoke/engine
  {:storage (memory/storage) :resources […]}."
  []
  (->MemoryStorage (atom {:tables {} :kinds {} :transitions []
                          :next-transition-id 0 :idempotency {}
                          :drafts {} :cursors {} :leases {}
                          :pending-notify []})
                   (Object.)
                   (atom 0)
                   (atom [])))
