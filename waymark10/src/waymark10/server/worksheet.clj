(ns waymark10.server.worksheet
  "The offline round-trip (paydesk's assignment workflow demanded it): a
  filtered collection view leaves as an xlsx workbook, a person edits
  it where waymark isn't, and the upload replays their edits through
  the kind's OWN declared actions — guards, audit trail, and (for
  mirrors) push-on-write all apply unchanged. The import endpoint
  never writes a field directly: it is a translator from cell diffs
  to invocations, and an id-less row becomes an ordinary create!
  (a :create-push mirror's post-commit pass claims its identity).

  Declared per kind as

     :worksheet
     {:columns [{:field :functional_role :action :change_role}
                {:field :ended_at :on-set {:action :end :param :ended_at}
                                  :on-clear {:action :reopen}}
                {:field :employee_zenefits_id :action :reassign
                 :param :employee_id :ref :employee}
                {:field :fund_external_id :param :fund_id :ref :fund
                 :create-only true}
                {:field :employee_name}]          ; no action: read-only
      :create true}

  Column grammar (gated at the def site — resource/check-worksheet!):
  :action invokes with {param cell} on a changed cell (param defaults
  to the field); :on-set/:on-clear split an optional field's set and
  cleared moves across two actions; :ref names a mirror kind whose
  external id the cell speaks — the import resolves it to a row id
  before it rides the input; :create-only participates in creation
  alone; a column with none of these exports as read-only context.

  The export reuses the collection query grammar verbatim, so the
  file holds exactly what the filtered view shows — unpaged up to
  export-cap, refused loudly past it (never a silent truncation).
  Each row leads with id / version / state: id matches the row back
  on upload, version is the optimistic-concurrency token (a row that
  moved since the download reports as a conflict, the edit skipped),
  state is context. dry_run=1 plans every row and applies nothing."
  (:require [clojure.string :as str]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.server.xlsx :as xlsx]
            [waymark10.wire :as wire])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.nio.charset StandardCharsets)
           (java.time Instant LocalDate ZoneOffset)))

(set! *warn-on-reflection* true)

(def export-cap
  "Rows an export serves before asking for a narrower filter."
  10000)

(def ^:private byte-cap
  "Upload size ceiling — far above any honest worksheet."
  (* 10 1024 1024))

(def ^:private reserved-headers ["id" "version" "state"])

(defn- spec-of [rdef]
  (or (:worksheet rdef)
      (throw (p/problem :not-found 404 "Not found"
                        {:detail "This kind declares no worksheet."}))))

(defn- editable? [col]
  (boolean (or (:action col) (:on-set col) (:on-clear col))))

(defn- field-json [rdef f]
  (schema/json-schema (schema/field-schema (:schema rdef) f)))

;; ── the value boundary ──────────────────────────────────────────────
;; Cells come back from a spreadsheet as nil | String | Double |
;; Boolean; the schema speaks wire values. Excel turns an edited date
;; into a day serial and a long id into a number — both convert by
;; the field's DECLARED type, never by guess.

(defn- num-str ^String [^double d]
  (if (and (== d (Math/floor d)) (not (Double/isInfinite d))
           (<= (Math/abs d) 9.0E15))
    (str (long d))
    (str d)))

(defn- date->instant-str
  "A bare ISO date spelled in a cell reads as UTC midnight; anything
  else passes through for the schema to judge."
  ^String [^String s]
  (if (re-matches #"\d{4}-\d{2}-\d{2}" s)
    (str (.toInstant (.atStartOfDay (LocalDate/parse s) ZoneOffset/UTC)))
    s))

(defn- coerce-cell
  "One sheet cell → the wire value the field's declared type speaks.
  A blank cell is nil — an emptied cell means unset, honestly."
  [js v]
  (let [t (:type js)
        fmt (:format js)
        s (when (string? v)
            (let [s (str/trim v)] (when-not (str/blank? s) s)))]
    (cond
      (nil? v) nil
      (and (string? v) (nil? s)) nil

      (#{"date-time" "date"} fmt)
      (cond (number? v) (xlsx/serial->instant-str (double v))
            :else (date->instant-str s))

      (= "boolean" t)
      (cond (boolean? v) v
            s (= "true" (str/lower-case s))
            :else (boolean v))

      (#{"number" "integer"} t)
      (cond (number? v) (bigdec (num-str (double v)))
            :else (bigdec ^String s))

      :else
      (cond s s
            (number? v) (num-str (double v))
            (boolean? v) (str v)
            :else (str v)))))

(defn- same-value?
  "Did the cell change the stored wire value? Numbers compare as
  decimals (0.5 and 0.50 agree), temporals as instants, everything
  else by canonical string."
  [js a b]
  (cond
    (and (nil? a) (nil? b)) true
    (or (nil? a) (nil? b)) false

    (#{"number" "integer"} (:type js))
    (try (zero? (.compareTo (bigdec (str a)) (bigdec (str b))))
         (catch Exception _ (= (str a) (str b))))

    (#{"date-time" "date"} (:format js))
    (try (= (Instant/parse (str a)) (Instant/parse (str b)))
         (catch Exception _ (= (str a) (str b))))

    :else (= (str a) (str b))))

(defn- export-cell [v]
  (cond
    (or (nil? v) (string? v) (number? v) (boolean? v)) v
    :else (wire/write-json v)))

;; ── export ──────────────────────────────────────────────────────────

(defn export
  "The filtered view as an xlsx response: the collection query
  grammar verbatim (pagination params ignored — a worksheet is the
  whole subset), one header row (id / version / state, then the
  declared columns), one row per match. Over export-cap refuses 422."
  [eng rdef params]
  (let [ws (spec-of rdef)
        {:keys [conds sort]} (collections/parse-query
                              rdef (dissoc params "page[size]" "page[number]"))
        st (:storage eng)
        rows (store/with-tx st
               (fn [tx]
                 (store/search-rows st tx (:kind rdef) conds
                                    {:order-by (:field sort)
                                     :desc (:desc sort)
                                     :limit (inc export-cap)})))]
    (when (> (count rows) export-cap)
      (throw (p/schema-invalid
              :worksheet
              {:rows [(str "over " export-cap
                           " rows match — narrow the filter and export again")]})))
    (let [cols (:columns ws)
          header (into reserved-headers (map (comp name :field)) cols)
          body (mapv (fn [raw]
                       (let [row (inv/decode-row rdef raw)
                             enc (schema/encode (:schema rdef) (:data row))]
                         (into [(str (:id row))
                                (:version row)
                                (name (:state row))]
                               (map (fn [c] (export-cell (get enc (:field c)))))
                               cols)))
                     rows)]
      {:status 200
       :headers {"Content-Type" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                 "Content-Disposition" (str "attachment; filename=\""
                                            (:plural rdef) ".xlsx\"")}
       :body (xlsx/write-sheet (into [header] body))})))

;; ── import ──────────────────────────────────────────────────────────

(defn- read-capped ^bytes [body]
  (cond
    (bytes? body) body
    (string? body) (.getBytes ^String body StandardCharsets/UTF_8)
    (instance? InputStream body)
    (let [out (ByteArrayOutputStream.)
          buf (byte-array 8192)]
      (loop [n 0]
        (let [r (.read ^InputStream body buf)]
          (if (neg? r)
            (.toByteArray out)
            (let [n (+ n r)]
              (when (> n byte-cap)
                (throw (p/problem :payload-too-large 413 "Workbook too large"
                                  {:detail (str "the upload passed " byte-cap
                                                " bytes")})))
              (.write out buf 0 r)
              (recur n))))))
    :else (throw (p/malformed-body "no workbook in the request body"))))

(defn- header-name [v]
  (cond
    (string? v) (let [s (str/trim v)] (when-not (str/blank? s) s))
    (some? v) (str v)
    :else nil))

(defn- resolve-ref
  "A :ref cell speaks the target kind's external id; the input wants
  the target's row id — one indexed lookup (the same closing move the
  mirror's external-keyed refs make)."
  [eng target xid]
  (store/with-tx (:storage eng)
    (fn [tx]
      (some-> (first (store/query-rows (:storage eng) tx target
                                       {:external_id (str xid)} {:limit 1}))
              :id str))))

(defn- ref-note [col cell]
  (str "no " (name (:ref col)) " carries external id " cell))

(defn- problem-reason [e]
  (let [d (ex-data e)]
    (or (:detail d)
        (when-some [errors (:errors d)]
          (str (:title d) ": "
               (str/join "; " (map (fn [[k v]] (str (name k) " " (str/join ", " v)))
                                   errors))))
        (:title d)
        (ex-message e))))

(defn- plan-row
  "One data row's cell diffs → {:invocations [[action input] …]
  :notes […] :blocked reason?}. Read-only cells that moved become
  notes, unresolvable refs block the single action, and same-valued
  cells plan nothing."
  [eng rdef cols col-idx sheet-row stored-enc]
  (reduce
   (fn [acc col]
     (let [f (:field col)
           idx (get col-idx (name f))]
       (if (nil? idx)
         acc
         (let [js (field-json rdef f)
               cell (coerce-cell js (get sheet-row idx))
               stored (get stored-enc f)]
           (cond
             (same-value? js cell stored) acc

             (not (editable? col))
             (update acc :notes conj
                     (str (name f) " is read-only in this worksheet — "
                          "the cell was ignored"))

             (:action col)
             (let [param (or (:param col) f)
                   v (if (and (:ref col) (some? cell))
                       (resolve-ref eng (:ref col) cell)
                       cell)]
               (if (and (:ref col) (some? cell) (nil? v))
                 (update acc :notes conj (ref-note col cell))
                 (update acc :invocations conj [(:action col) {param v}])))

             :else                                        ; :on-set / :on-clear
             (let [{:keys [on-set on-clear]} col]
               (cond
                 (and (nil? cell) on-clear)
                 (update acc :invocations conj
                         [(:action on-clear)
                          (when-some [param (:param on-clear)] {param nil})])

                 (and (some? cell) on-set)
                 (update acc :invocations conj
                         [(:action on-set)
                          (when-some [param (:param on-set)] {param cell})])

                 :else
                 (update acc :notes conj
                         (str (name f) ": no "
                              (if (nil? cell) ":on-clear" ":on-set")
                              " action declared — the cell was ignored")))))))))
   {:invocations [] :notes []}
   cols))

(defn- create-body
  "An id-less row's participating cells (an :action or :create-only
  column, keyed by its param) → the create! body; a resolvable :ref
  cell rides as the target's row id. Returns {:body … :notes […]}."
  [eng rdef cols col-idx sheet-row]
  (reduce
   (fn [acc col]
     (let [f (:field col)
           idx (get col-idx (name f))]
       (if (or (nil? idx) (not (or (:action col) (:create-only col))))
         acc
         (let [cell (coerce-cell (field-json rdef f) (get sheet-row idx))
               param (or (:param col) f)]
           (cond
             (nil? cell) acc
             (:ref col)
             (if-some [rid (resolve-ref eng (:ref col) cell)]
               (assoc-in acc [:body param] rid)
               (update acc :notes conj (ref-note col cell)))
             :else (assoc-in acc [:body param] cell))))))
   {:body {} :notes []}
   cols))

(defn- apply-invocations!
  "Each planned action as an honest client would invoke it: the
  row's CURRENT etag when the action is fenced (the worksheet's own
  staleness gate already ran against the sheet's version column; the
  fence guards the moments between this import's own writes), a
  fresh key when non-idempotent. One refusal never poisons the row's
  other edits, and every verdict is reported."
  [eng rdef id invocations {:keys [principal correlation-id]}]
  (let [kind (:kind rdef)]
    (reduce
     (fn [acc [action input]]
       (try
         (let [a (get-in rdef [:actions action])
               fenced? (get-in a [:safety :fence])
               version (when fenced?
                         (:version (store/with-tx (:storage eng)
                                     (fn [tx]
                                       (store/load-row (:storage eng) tx
                                                       kind id {})))))]
           (inv/invoke! eng kind id action input
                        (cond-> {:principal principal
                                 :correlation-id correlation-id}
                          fenced?
                          (assoc :if-match (inv/etag kind id version))
                          (not (get-in a [:safety :idempotent]))
                          (assoc :idempotency-key (str (random-uuid))))))
         (update acc :applied conj (name action))
         (catch Exception e
           (update acc :refused conj
                   (str (name action) ": " (problem-reason e))))))
     {:applied [] :refused []}
     invocations)))

(defn- merge-invocations
  "Two columns riding one action merge their inputs into one invoke."
  [invocations]
  (reduce (fn [acc [action input]]
            (if-some [i (some (fn [[j [a _]]] (when (= a action) j))
                              (map-indexed vector acc))]
              (update-in acc [i 1] #(if (or %1 %2) (merge %1 %2) nil) input)
              (conj acc [action input])))
          []
          invocations))

(defn- load-stored [eng rdef id]
  (let [st (:storage eng)]
    (some->> (store/with-tx st
               (fn [tx] (store/load-row st tx (:kind rdef) id {})))
             (inv/decode-row rdef))))

(defn- outcome [line & kvs]
  (into {:line line} (map vec (partition 2 kvs))))

(defn import!
  "One uploaded workbook against the declared worksheet: match each
  data row by its id cell, refuse stale versions as conflicts, plan
  the cell diffs, and (unless dry-run) replay them through the kind's
  own actions; an id-less row creates (when the worksheet declares
  :create true), then its :on-set cells apply as ordinary edits on
  the fresh row. Returns the per-row report — applied / created /
  unchanged / conflict / refused (dry-run: planned), with notes for
  everything ignored. opts: :principal (required), :dry-run,
  :allowed? (action-name → bool, the router's grant projection)."
  [eng rdef body {:keys [principal dry-run allowed?]
                  :or {allowed? (constantly true)}}]
  (let [ws (spec-of rdef)
        kind (:kind rdef)
        sheet (try (xlsx/read-sheet (read-capped body))
                   (catch clojure.lang.ExceptionInfo e
                     (if (:waymark10/problem (ex-data e))
                       (throw e)
                       (throw (p/malformed-body
                               "the body is not an xlsx workbook"))))
                   (catch Exception _
                     (throw (p/malformed-body
                             "the body is not an xlsx workbook"))))
        headers (first sheet)
        col-idx (into {}
                      (keep-indexed (fn [i h]
                                      (when-some [n (header-name h)] [n i])))
                      headers)
        _ (when-not (contains? col-idx "id")
            (throw (p/schema-invalid
                    :worksheet
                    {:id ["the header row names no id column — export first, edit that file"]})))
        cols (:columns ws)
        correlation-id (str (random-uuid))
        opts {:principal principal :correlation-id correlation-id}
        gate (fn [invocations]
               (some (fn [[a _]] (when-not (allowed? a)
                                   (str (name a) " is not available here")))
                     invocations))
        results
        (into
         []
         (keep-indexed
          (fn [i sheet-row]
            (let [line (+ 2 i)]                  ; 1-based, after the header
              (when (some some? sheet-row)       ; trailing blanks skip
                (let [id-cell (coerce-cell {:type "string"}
                                           (get sheet-row (col-idx "id")))
                      version-cell (some->> (col-idx "version")
                                            (get sheet-row)
                                            (coerce-cell {:type "integer"}))]
                  (cond
                    ;; ── an id-less row is a birth ────────────────────
                    (nil? id-cell)
                    (cond
                      (not (:create ws))
                      (outcome line :outcome "refused"
                               :reason "this worksheet does not create rows — the id cell is empty")

                      (not (allowed? :create))
                      (outcome line :outcome "refused"
                               :reason "create is not available here")

                      :else
                      (let [{:keys [body notes]} (create-body eng rdef cols
                                                              col-idx sheet-row)]
                        (if dry-run
                          (outcome line :outcome "planned" :actions ["create"]
                                   :notes notes)
                          (let [created
                                (try {:row (:row (inv/create! eng kind body
                                                              {:principal principal
                                                               :correlation-id correlation-id}))}
                                     (catch Exception e
                                       {:refused (problem-reason e)}))]
                            (if-some [reason (:refused created)]
                              (outcome line :outcome "refused" :reason reason
                                       :notes notes)
                              ;; the fresh row takes the :on-set cells
                              ;; (an imported row born already-ended) as
                              ;; ordinary edits — one uniform second pass
                              (let [row (:row created)
                                    enc (schema/encode (:schema rdef) (:data row))
                                    plan (plan-row eng rdef
                                                   (filterv #(or (:on-set %) (:on-clear %)) cols)
                                                   col-idx sheet-row enc)
                                    {:keys [applied refused]}
                                    (apply-invocations!
                                     eng rdef (:id row)
                                     (merge-invocations (:invocations plan)) opts)]
                                (outcome line :outcome "created"
                                         :self (str "/api/" (:plural rdef) "/" (:id row))
                                         :actions (into ["create"] applied)
                                         :refusals refused
                                         :notes (into (vec notes) (:notes plan)))))))))

                    ;; ── an id names a stored row ─────────────────────
                    :else
                    (let [row (load-stored eng rdef id-cell)]
                      (cond
                        (nil? row)
                        (outcome line :id id-cell :outcome "refused"
                                 :reason "no such row — was it deleted since the export?")

                        (and (some? version-cell)
                             (not= (long version-cell) (long (:version row))))
                        (outcome line :id id-cell :outcome "conflict"
                                 :reason (str "the row changed since the export "
                                              "(version " (:version row)
                                              " now; the sheet held " (long version-cell)
                                              ") — re-export and redo this edit"))

                        :else
                        (let [enc (schema/encode (:schema rdef) (:data row))
                              {:keys [invocations notes]}
                              (plan-row eng rdef cols col-idx sheet-row enc)
                              invocations (merge-invocations invocations)]
                          (cond
                            (empty? invocations)
                            (outcome line :id id-cell :outcome "unchanged"
                                     :notes notes)

                            (gate invocations)
                            (outcome line :id id-cell :outcome "refused"
                                     :reason (gate invocations) :notes notes)

                            dry-run
                            (outcome line :id id-cell :outcome "planned"
                                     :actions (mapv (comp name first) invocations)
                                     :notes notes)

                            :else
                            (let [{:keys [applied refused]}
                                  (apply-invocations! eng rdef (:id row)
                                                      invocations opts)]
                              (outcome line :id id-cell
                                       :outcome (if (seq applied)
                                                  "applied"
                                                  "refused")
                                       :actions applied
                                       :refusals refused
                                       :notes notes))))))))))))
         (rest sheet))
        tally (frequencies (map :outcome results))]
    {:kind (str (name kind) "_worksheet_report")
     :dry_run (boolean dry-run)
     :summary (str (count results) " row(s): "
                   (str/join ", " (map (fn [[o n]] (str n " " o))
                                       (sort tally))))
     :data {:rows results
            :tally tally
            :correlation_id correlation-id}}))
