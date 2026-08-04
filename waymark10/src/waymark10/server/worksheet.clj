(ns waymark10.server.worksheet
  "The offline round-trip (paydesk's assignment workflow demanded it): a
  filtered collection view leaves as an xlsx workbook, a person edits
  it where waymark isn't, and the upload comes back as a RESOURCE —
  the worksheet kind, the engine's own — whose row is the batch: the
  staged edits, the validation report, and the outcomes are all data,
  so a broken upload is a navigable, auditable thing a person can fix
  and retry, never a dialog's transient state.

  The machine:

     staged ──apply──▶ applying ──record_outcomes──▶ applied
        │ ▲                          (system)
        │ └─ revalidate / record_report (re-plan against today's rows)
        └──discard──▶ discarded

  A POST of workbook bytes to /api/:plural/-/worksheet STAGES: the
  sheet parses into normalized per-line cells (coerced by each
  field's declared type) and lands as an ordinary create! of a
  worksheet row — the same door a JSON client may use directly. The
  post-commit pass (after-write!, wired by the engine boot) computes
  the plan — which lines create, which replay which actions, which
  conflict on a stale version, which cells are read-only — and
  records it through the system door, so the upload's 201 already
  carries the full report. Fix what it names (in the app, or in the
  file and upload afresh), revalidate to re-plan, then apply: the
  pass replays every line through the TARGET kind's own declared
  actions AS THE PERSON WHO APPLIED — guards, audit, push-on-write
  all unchanged; the import never writes a field directly — and the
  outcomes land as the applied row's report.

  Per target kind the surface is declared as

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

  (gated at the def site — resource/check-worksheet!): :action
  invokes with {param cell} on a changed cell (param defaults to the
  field); :on-set/:on-clear split an optional field's set and cleared
  moves across two actions; :ref names a mirror kind whose external
  id the cell speaks — resolved to a row id before it rides the
  input; :create-only participates in creation alone; a column with
  none of these exports as read-only context. An id-less line rides
  the target's create door (a :create-push mirror's post-commit pass
  claims its minted identity).

  The export reuses the collection query grammar verbatim, so the
  file holds exactly what the filtered view shows — unpaged up to
  export-cap, refused loudly past it (never a silent truncation).
  Each row leads with id / version / state: id matches the line back
  on upload, version is the optimistic-concurrency token (a row that
  moved since the download reports as a conflict, the edit skipped),
  state is context.

  The worksheet kind enrolls only when some application kind declares
  :worksheet — an app without the round-trip grows no extra kind."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.server.xlsx :as xlsx]
            [waymark10.summary :as summary]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.nio.charset StandardCharsets)
           (java.time Instant LocalDate ZoneOffset)))

(set! *warn-on-reflection* true)

(def export-cap
  "Rows an export serves — and lines an upload stages — before asking
  for a narrower filter."
  10000)

(def ^:private byte-cap
  "Upload size ceiling — far above any honest worksheet."
  (* 10 1024 1024))

(def ^:private reserved-headers ["id" "version" "state"])

(def runner
  "The system actor the post-commit pass plans and records through."
  (t/principal {:id "waymark10-worksheet" :type :system
                :display "Worksheet runner"}))

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
  declared columns), one row per match. Over export-cap refuses 422.
  A visibility (the per-request grant projection) narrows the file
  exactly as it narrows the collection envelope: the query oracle
  judges requested filters and sorts, a non-plain default sort softens
  to natural order, the leash's ids/conds AND into the search (so the
  rows and the cap count tell the grant's story), columns project by
  field admission, and a hashed field's cells land as tokens."
  ([eng rdef params] (export eng rdef params nil))
  ([eng rdef params vis]
   (let [ws (spec-of rdef)
         {:keys [conds sort]} (collections/parse-query
                               rdef (dissoc params "page[size]" "page[number]"))
         _ (grants/check-query! vis rdef (remove :default? conds)
                                (when (contains? params "sort")
                                  (:field sort)))
         sort (if (and vis (:field sort)
                       (not (#{:id :state} (:field sort)))
                       (not (grants/plain-field? vis (:kind rdef)
                                                 (:field sort))))
                (assoc sort :field nil)
                sort)
         conds (if-some [ids (when vis ((:ids-of vis) (:kind rdef)))]
                 (conj conds {:target :id :op :in :values (vec ids)})
                 conds)
         conds (if-some [fconds (when (and vis (:conds-of vis))
                                  ((:conds-of vis) (:kind rdef)))]
                 (into conds fconds)
                 conds)
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
     (let [kind (:kind rdef)
           cols (cond->> (:columns ws)
                  (:field? vis) (filterv #((:field? vis) kind (:field %))))
           hashed? (:hashed? vis)
           header (into reserved-headers (map (comp name :field)) cols)
           body (mapv (fn [raw]
                        (let [row (inv/decode-row rdef raw)
                              enc (schema/encode (:schema rdef) (:data row))]
                          (into [(str (:id row))
                                 (:version row)
                                 (name (:state row))]
                                (map (fn [c]
                                       (let [f (:field c)]
                                         ;; the hashed disposition: the
                                         ;; token of the DECODED value —
                                         ;; encoding ran over the
                                         ;; original, tokens land after
                                         ;; (render's own order)
                                         (if (and hashed? (hashed? kind f))
                                           (when (contains? (:data row) f)
                                             ((:hash vis) kind f
                                              (get-in row [:data f])))
                                           (export-cell (get enc f))))))
                                cols)))
                      rows)]
       {:status 200
        :headers {"Content-Type" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                  "Content-Disposition" (str "attachment; filename=\""
                                             (:plural rdef) ".xlsx\"")}
        :body (xlsx/write-sheet (into [header] body))}))))

;; ── staging: workbook bytes → normalized lines ──────────────────────

(defn read-capped ^bytes [body]
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

(defn stage-lines
  "One parsed sheet → the worksheet row's :lines — per data line, its
  id and version cells plus every declared-column cell coerced to the
  field's wire type; a column absent from the sheet stays absent from
  :cells (unknown headers are ignored; blank trailing lines skip).
  Line numbers are the spreadsheet's own (1-based, after the header)."
  [rdef sheet]
  (let [ws (spec-of rdef)
        headers (first sheet)
        col-idx (into {}
                      (keep-indexed (fn [i h]
                                      (when-some [n (header-name h)] [n i])))
                      headers)]
    (when-not (contains? col-idx "id")
      (throw (p/schema-invalid
              :worksheet
              {:id ["the header row names no id column — export first, edit that file"]})))
    (into []
          (keep-indexed
           (fn [i sheet-row]
             (when (some some? sheet-row)
               (let [id (coerce-cell {:type "string"}
                                     (get sheet-row (col-idx "id")))
                     version (some->> (col-idx "version")
                                      (get sheet-row)
                                      (coerce-cell {:type "integer"}))
                     cells (into {}
                                 (keep (fn [col]
                                         (let [f (:field col)]
                                           (when-some [idx (get col-idx (name f))]
                                             [f (coerce-cell (field-json rdef f)
                                                             (get sheet-row idx))]))))
                                 (:columns ws))]
                 (cond-> {:line (+ 2 i) :cells cells}
                   id (assoc :id id)
                   version (assoc :version (long version)))))))
          (rest sheet))))

;; ── planning and applying one staged line ───────────────────────────

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

(defn- ref-display
  "One resolved ref cell as the report speaks it: the target row's
  address and its own declared summary — a person or a fund, not an
  external id. Nil when nothing carries the id (the plan's note
  already names that)."
  [eng target xid]
  (when-some [raw (store/with-tx (:storage eng)
                    (fn [tx]
                      (first (store/query-rows (:storage eng) tx target
                                               {:external_id (str xid)}
                                               {:limit 1}))))]
    (let [trdef (get (inv/resources eng) target)
          row (inv/decode-row trdef raw)]
      {:self (str "/api/" (:plural trdef) "/" (:id row))
       :display (summary/render (:summary trdef) row)})))

(defn- line-refs
  "Every :ref cell the line carries, resolved for display:
  {field {:self … :display …}}."
  [eng rdef cells]
  (into {}
        (keep (fn [{:keys [field ref]}]
                (when (and ref (some? (get cells field)))
                  (when-some [d (ref-display eng ref (get cells field))]
                    [field d]))))
        (:columns (:worksheet rdef))))

(defn- with-line-refs
  "The line's resolved refs stamped on its report entry, so the
  report renders links where the sheet spoke external ids."
  [eng rdef cells entry]
  (let [refs (line-refs eng rdef cells)]
    (cond-> entry (seq refs) (assoc :refs refs))))

(defn- problem-reason [e]
  (let [d (ex-data e)]
    (or (:detail d)
        (when-some [errors (:errors d)]
          (str (:title d) ": "
               (str/join "; " (map (fn [[k v]] (str (name k) " " (str/join ", " v)))
                                   errors))))
        (:title d)
        (ex-message e))))

(defn- plan-cells
  "One line's cells against one stored row's encoded data →
  {:invocations [[action input] …] :notes […]}. Read-only cells that
  moved become notes, unresolvable refs block their one action, and
  same-valued cells plan nothing."
  [eng rdef cells stored-enc]
  (reduce
   (fn [acc col]
     (let [f (:field col)]
       (if-not (contains? cells f)
         acc
         (let [js (field-json rdef f)
               cell (get cells f)
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
   (:columns (:worksheet rdef))))

(defn- create-body
  "An id-less line's participating cells (an :action or :create-only
  column, keyed by its param) → the create! body; a resolvable :ref
  cell rides as the target's row id. Returns {:body … :notes […]}."
  [eng rdef cells]
  (reduce
   (fn [acc col]
     (let [f (:field col)]
       (if (or (not (contains? cells f))
               (not (or (:action col) (:create-only col))))
         acc
         (let [cell (get cells f)
               param (or (:param col) f)]
           (cond
             (nil? cell) acc
             (:ref col)
             (if-some [rid (resolve-ref eng (:ref col) cell)]
               (assoc-in acc [:body param] rid)
               (update acc :notes conj (ref-note col cell)))
             :else (assoc-in acc [:body param] cell))))))
   {:body {} :notes []}
   (:columns (:worksheet rdef))))

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

(defn- plan-line
  "The rehearsal for one staged line: what WOULD happen, against the
  target rows as they stand right now — its :ref cells resolved so
  the report reads as the rows they name."
  [eng rdef {:keys [line id version cells]}]
  (with-line-refs eng rdef cells
    (if (nil? id)
      (if-not (:create (:worksheet rdef))
        (outcome line :outcome "refused"
                 :reason "this worksheet does not create rows — the id cell is empty")
        (let [{:keys [body notes]} (create-body eng rdef cells)
              ;; the create door's own rehearsal judges the body —
              ;; schema and create guards, nothing fired
              refused (try (inv/create! eng (:kind rdef) body
                                        {:principal runner :dry-run true})
                           nil
                           (catch Exception e (problem-reason e)))
              on-sets (filterv #(and (contains? cells (:field %))
                                     (some? (get cells (:field %)))
                                     (:on-set %))
                               (:columns (:worksheet rdef)))]
          (if refused
            (outcome line :outcome "refused" :reason refused :notes notes)
            (outcome line :outcome "planned"
                     :actions (into ["create"]
                                    (map (comp name :action :on-set)) on-sets)
                     :notes notes))))
      (let [row (load-stored eng rdef id)]
        (cond
          (nil? row)
          (outcome line :id id :outcome "refused"
                   :reason "no such row — was it deleted since the export?")

          (and (some? version) (not= (long version) (long (:version row))))
          (outcome line :id id :outcome "conflict"
                   :reason (str "the row changed since the export (version "
                                (:version row) " now; the sheet held " version
                                ") — re-export and redo this edit, or revalidate "
                                "if the change was this worksheet's own"))

          :else
          (let [enc (schema/encode (:schema rdef) (:data row))
                {:keys [invocations notes]} (plan-cells eng rdef cells enc)]
            (if (empty? invocations)
              (outcome line :id id :outcome "unchanged" :notes notes)
              (outcome line :id id :outcome "planned"
                       :actions (mapv (comp name first)
                                      (merge-invocations invocations))
                       :notes notes))))))))

(defn- apply-invocations!
  "Each planned action as an honest client would invoke it: the
  row's CURRENT etag when the action is fenced, a fresh key when
  non-idempotent. One refusal never poisons the line's other edits."
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

(defn- run-line!
  "One staged line for real: creates create (then their :on-set cells
  apply as ordinary edits on the fresh row — one uniform second
  pass), edits replay, stale versions conflict, and the outcome says
  exactly what happened — its :ref cells resolved as in the plan."
  [eng rdef {:keys [line id version cells]} opts]
  (with-line-refs eng rdef cells
    (if (nil? id)
      (cond
        (not (:create (:worksheet rdef)))
        (outcome line :outcome "refused"
                 :reason "this worksheet does not create rows — the id cell is empty")

        :else
        (let [{:keys [body notes]} (create-body eng rdef cells)
              created (try {:row (:row (inv/create! eng (:kind rdef) body opts))}
                           (catch Exception e {:refused (problem-reason e)}))]
          (if-some [reason (:refused created)]
            (outcome line :outcome "refused" :reason reason :notes notes)
            (let [row (:row created)
                  enc (schema/encode (:schema rdef) (:data row))
                  on-set-cols (filterv #(or (:on-set %) (:on-clear %))
                                       (:columns (:worksheet rdef)))
                  plan (plan-cells eng (assoc-in rdef [:worksheet :columns]
                                                 on-set-cols)
                                   cells enc)
                  {:keys [applied refused]}
                  (apply-invocations! eng rdef (:id row)
                                      (merge-invocations (:invocations plan))
                                      opts)]
              (outcome line :outcome "created"
                       :self (str "/api/" (:plural rdef) "/" (:id row))
                       :actions (into ["create"] applied)
                       :refusals refused
                       :notes (into (vec notes) (:notes plan)))))))
      (let [row (load-stored eng rdef id)]
        (cond
          (nil? row)
          (outcome line :id id :outcome "refused"
                   :reason "no such row — was it deleted since the export?")

          (and (some? version) (not= (long version) (long (:version row))))
          (outcome line :id id :outcome "conflict"
                   :reason (str "the row changed since the export (version "
                                (:version row) " now; the sheet held " version
                                ") — re-export and redo this edit"))

          :else
          (let [enc (schema/encode (:schema rdef) (:data row))
                {:keys [invocations notes]} (plan-cells eng rdef cells enc)
                invocations (merge-invocations invocations)]
            (if (empty? invocations)
              (outcome line :id id :outcome "unchanged" :notes notes)
              (let [{:keys [applied refused]}
                    (apply-invocations! eng rdef id invocations opts)]
                (outcome line :id id
                         :outcome (if (seq applied) "applied" "refused")
                         :actions applied
                         :refusals refused
                         :notes notes)))))))))

;; ── the worksheet kind ──────────────────────────────────────────────

(def ^:private system-only
  (g/guard
   {:name :worksheet-system-only
    :explain "The report is the runner's record; people revalidate and apply."
    :reads [:principal]
    :hide true
    :check (with-meta
             (fn [_ _ ctx]
               (if (= :system (:type (:principal ctx)))
                 (t/allow)
                 (t/deny)))
             {:waymark10/form '(fn [row inp ctx]
                                 (waymark10.server.worksheet/system-principal?
                                  ctx))})}))

(def ^:private record-input
  [:map
   [:report [:vector [:map-of :keyword :any]]]
   [:tally [:map-of :keyword :int]]])

(def ^:private record-report-handler
  (with-meta
    (fn [row inp ctx]
      (update row :data assoc
              :report (:report inp)
              :tally (:tally inp)
              :checked_at (:now ctx)))
    {:waymark10/form '(fn [row inp ctx]
                        (waymark10.server.worksheet/record-report
                         row inp ctx))}))

(def ^:private line-schema
  [:map
   [:line [:int {:min 1}]]
   [:id {:optional true} [:maybe [:string {:max 64}]]]
   [:version {:optional true} [:maybe [:int {:min 1}]]]
   [:cells [:map-of :keyword :any]]])

(defn worksheet-resource
  "The engine's worksheet kind, its create door's :target enum baked
  from the kinds that declare a worksheet."
  [targets]
  (r/resource
   {:kind :worksheet
    :plural "worksheets"
    :states [:staged :applying :applied :discarded]
    :initial :staged
    :terminal #{:applied :discarded}
    :nav :system
    :summary "{data.target} worksheet · {state}"
    :label-template "{data.target} worksheet"
    :schema [:map
             [:target (into [:enum] targets)]
             [:filename {:optional true} [:maybe [:string {:max 200}]]]
             ;; the staged edits — machine food, rendered by the
             ;; report, not as a form
             [:lines {:x-display {:hidden true}}
              [:vector {:max 10000} line-schema]]
             ;; the per-line truth: the plan while staged, the
             ;; outcomes once applied
             [:report {:optional true :x-display {:hidden true}}
              [:maybe [:vector [:map-of :keyword :any]]]]
             [:tally {:optional true :x-display {:raw true}}
              [:maybe [:map-of :keyword :int]]]
             [:checked_at {:optional true} [:maybe :waymark/instant]]
             [:applied_at {:optional true} [:maybe :waymark/instant]]]
    :filterable {:state #{:eq :in} :target #{:eq}}
    :sortable {:fields [:checked_at] :default "-checked_at"}
    :actions
    {:revalidate
     {:from #{:staged} :to :staged
      :safety {:idempotent false :reversible true :confirm false}
      :display {:label "Revalidate" :order 2
                :description "Re-plan every line against the rows as they stand now."}}
     :apply
     {:from #{:staged} :to :applying
      :safety {:idempotent false :reversible false :confirm true
               :consequence "Every planned line replays through the target's own actions as you; conflicted and refused lines are skipped and reported."}
      :display {:label "Apply" :style :primary :order 1}}
     :discard
     {:from #{:staged :applying} :to :discarded
      :safety {:idempotent true :reversible false :confirm true
               :consequence "The staged edits are abandoned; the row stays as the audited record."}
      :display {:label "Discard" :style :danger :order 9}}
     :record_report
     {:from #{:staged} :to :staged
      :input record-input
      :guards [system-only]
      :edit {:prefill [:report] :fence false
             :unfenced-reason "A system-only record inside the post-commit pass — no read preceded it to fence against."}
      :safety {:idempotent true :reversible false :confirm false
               :one-way "The runner's rehearsal record; revalidate refreshes it."}
      :handler record-report-handler
      :display {:label "Recorded the plan"}}
     :record_outcomes
     {:from #{:applying} :to :applied
      :input record-input
      :guards [system-only]
      :edit {:prefill [:report] :fence false
             :unfenced-reason "A system-only record inside the post-commit pass — no read preceded it to fence against."}
      :safety {:idempotent true :reversible false :confirm false
               :one-way "What the apply did, line by line; the target rows' own audit trails carry the detail."}
      :handler (with-meta
                 (fn [row inp ctx]
                   (update row :data assoc
                           :report (:report inp)
                           :tally (:tally inp)
                           :applied_at (:now ctx)))
                 {:waymark10/form '(fn [row inp ctx]
                                     (waymark10.server.worksheet/record-outcomes
                                      row inp ctx))})
      :display {:label "Recorded the outcomes"}}}}))

(defn kinds
  "The engine's worksheet kind(s) for this application — empty when
  no kind declares :worksheet (no round-trip, no extra kind)."
  [resources]
  (let [targets (into []
                      (comp (filter :worksheet) (map (comp name :kind)))
                      resources)]
    (if (empty? targets)
      []
      [(worksheet-resource targets)])))

;; ── the post-commit pass ────────────────────────────────────────────

(defn- target-rdef [eng data]
  (get (inv/resources eng) (keyword (:target data))))

(defn- tally-of [report]
  (into {}
        (map (fn [[o n]] [(keyword o) n]))
        (frequencies (map :outcome report))))

(defn- record! [eng id action report]
  (:row (inv/invoke! eng :worksheet id action
                     {:report report :tally (tally-of report)}
                     {:principal runner})))

(defn- principal-of-transition [res]
  (let [a (get-in res [:transition :actor])]
    (t/principal {:id (:id a)
                  :type (keyword (or (:type a) "human"))
                  :display (:display a)})))

(defn after-write!
  "The worksheet pass on the engine's post-commit :maintain hook
  (wired by the engine boot, the same seam as the mirror push pass):
  a committed create or revalidate PLANS — every line rehearsed
  against the target rows as they stand — and records the report
  through the system door, so the response already carries it; a
  committed apply RUNS the lines as the person who applied (their
  actions, their audit) and records the outcomes, landing the row
  applied. Returns res with :row refreshed to the recorded truth."
  [eng kind action-name res]
  (if-not (and (= :worksheet kind) (:transition res) (nil? (:replayed? res)))
    res
    (let [row (:row res)
          data (:data row)
          rdef (target-rdef eng data)
          gone (fn []
                 (mapv (fn [{:keys [line id]}]
                         (cond-> (outcome line :outcome "refused"
                                          :reason (str "kind " (:target data)
                                                       " no longer declares a worksheet"))
                           id (assoc :id id)))
                       (:lines data)))]
      (cond
        (or (= :revalidate action-name)
            (contains? (:create-action-names
                        (get (inv/resources eng) :worksheet))
                       action-name))
        (assoc res :row
               (record! eng (:id row) :record_report
                        (if (and rdef (:worksheet rdef))
                          (mapv #(plan-line eng rdef %) (:lines data))
                          (gone))))

        (= :apply action-name)
        (let [principal (principal-of-transition res)
              opts {:principal principal
                    :correlation-id (get-in res [:transition :correlation-id])}]
          (assoc res :row
                 (record! eng (:id row) :record_outcomes
                          (if (and rdef (:worksheet rdef))
                            (mapv #(run-line! eng rdef % opts) (:lines data))
                            (gone)))))

        :else res))))

;; ── staging from workbook bytes (the route's door) ──────────────────

(defn stage!
  "POST /api/:plural/-/worksheet: workbook bytes → an ordinary
  create! of a worksheet row (staged); the post-commit pass records
  the plan before the response renders. Returns the create! result."
  [eng rdef body {:keys [principal filename]}]
  (let [sheet (try (xlsx/read-sheet (read-capped body))
                   (catch clojure.lang.ExceptionInfo e
                     (if (:waymark10/problem (ex-data e))
                       (throw e)
                       (throw (p/malformed-body
                               "the body is not an xlsx workbook"))))
                   (catch Exception _
                     (throw (p/malformed-body
                             "the body is not an xlsx workbook"))))
        lines (stage-lines rdef sheet)]
    (inv/create! eng :worksheet
                 (cond-> {:target (name (:kind rdef))
                          :lines lines}
                   filename (assoc :filename filename))
                 {:principal principal})))
