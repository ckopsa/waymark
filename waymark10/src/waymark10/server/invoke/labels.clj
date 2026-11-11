(ns waymark10.server.invoke.labels
  "The engine's passes over a write's data (moved out of
  waymark10.server.invoke, ticket 2d02c8ab): the row's encoding and
  summary line, the live :unique refusal, the maintained ref labels
  (design §4), the documents an :accept refuses and one-of
  enforcement. Each runs inside the write's own transaction."
  (:require [clojure.string :as str]
            [waymark10.groups :as groups]
            [waymark10.schema :as schema]
            [waymark10.server.invoke.rows :refer [decode-row resources]]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn encode-row [rdef row]
  (update row :data #(schema/encode (:schema rdef) %)))

(defn summary-of
  "The row's line, read on the engine's clock `now` (ticket d10e6c45)."
  [rdef row now]
  (summary/line rdef (assoc row :kind (:kind rdef)) now))

(defn log-summarizer
  "A fn of one of the row's log lines → the kind's summary line
  rendered from that line's retained document, for a purge's
  redact-log! (ticket a2e8297c)."
  [rdef now]
  (fn [rec]
    (summary-of rdef {:id (:resource-id rec) :state (:to-state rec)
                      :data (:after rec)}
                now)))

(defn live-unique-clash!
  "A :unique group narrowed by :while refuses, in the write's own
  transaction, a row that would be a second live one under the same
  values, naming the live one by its line. The partial index stays the
  law under a race: two writes at once meet it, and the second is the
  plain 409."
  [engine tx rdef row]
  (let [state (name (:state row))
        words #(str/replace (str/replace (name %) #"_id$" "") "_" " ")]
    (doseq [{:keys [fields while]} (t/unique-groups (:unique rdef))
            :when (contains? (into #{} (map name) while) state)
            :let [data (:data (encode-row rdef row))]
            :when (every? #(some? (get data %)) fields)
            s (sort-by name while)
            other (store/query-rows (:storage engine) tx (:kind rdef)
                                    (into {:state s}
                                          (map (fn [f] [f (get data f)]))
                                          fields)
                                    {:limit 2})
            :when (not= (:id other) (:id row))]
      (let [line (summary-of rdef (decode-row rdef other) ((:now-fn engine)))]
        (throw (p/unique-live
                (str line " is already " (words s) " for this "
                     (str/join " and " (map words fields))
                     "; finish or drop it first.")
                {:id (:id other) :summary line}))))))

;; ── ref labels (phase 8, design §4) ─────────────────────────────────

(defn schema-head*
  "The leaf type of an entry's schema form, :maybe unwrapped."
  [s]
  (let [s (if (and (vector? s) (= :maybe (first s))) (second s) s)]
    (if (vector? s) (first s) s)))

(defn item-map-form
  "The [:map …] item form of a vector-of-map field, nil otherwise."
  [form k]
  (when-some [s (schema/field-schema form k)]
    (when (and (vector? s) (= :vector (first s)))
      (let [item (last s)]
        (when (and (vector? item) (= :map (first item)))
          item)))))

(defn labeled-ref-specs
  "field → {:kind … :label … :carry …} for every :waymark/ref entry
  of one :map form that declares a :label (the maintained label
  garnish) or a :carry ({source target} — further target data fields
  copied alongside, the label doctrine for facts the referring row
  wants at a glance)."
  [form]
  (into {}
        (keep (fn [[f {:keys [properties schema]}]]
                (when (and (= :waymark/ref (schema-head* schema))
                           (:kind properties)
                           (or (:label properties) (:carry properties)))
                  [f (select-keys properties [:kind :label :carry])])))
        (schema/entry-map form)))

(defn target-of
  "The referenced row, decoded and kind-stamped; nil when the target
  is gone (dangling refs are the guards' problem, loudly — never the
  garnish pass's)."
  [engine tx target-kind id]
  (when-some [trdef (get (resources engine) target-kind)]
    (some->> (store/load-row (:storage engine) tx target-kind (str id) {})
             (decode-row trdef)
             (#(assoc % :kind target-kind)))))

(defn label-of
  "The target's declared label: its :label-template, defaulting to
  \"{data.name}\" when the target schema declares :name; nil when the
  target is unlabelable."
  [engine target]
  (let [trdef (get (resources engine) (:kind target))]
    (when-some [template
                (or (:label-template trdef)
                    (when (contains? (set (schema/entry-keys (:schema trdef)))
                                     :name)
                      "{data.name}"))]
      (summary/render template target))))

(defn label-pass [engine tx specs m before]
  (reduce-kv
   (fn [m f {:keys [kind label carry]}]
     (let [new-id (get m f)]
       (cond
         (= new-id (get before f)) m

         (nil? new-id)
         (reduce (fn [m dst] (assoc m dst nil))
                 m
                 (cond-> (vec (vals carry)) label (conj label)))

         :else
         (if-some [target (target-of engine tx kind new-id)]
           (reduce-kv (fn [m src dst]
                        (assoc m dst (get-in target [:data src])))
                      (if-some [lv (when label (label-of engine target))]
                        (assoc m label lv)
                        m)
                      carry)
           m))))
   m
   specs))

(defn maintain-ref-labels
  "Denormalized ref labels are generated, never hand-copied: whenever
  a write changes a labeled ref — data root or vector-of-map items —
  the engine reads the target in the same transaction and writes its
  label. before nil (create) treats every set ref as changed. Recorded
  scope: labels refresh at the write that SETS the ref; a target
  rename does not fan back out (named punt — waymark9's maintainer
  did)."
  [engine tx rdef data before]
  (let [root-specs (labeled-ref-specs (:schema rdef))
        data (if (seq root-specs)
               (label-pass engine tx root-specs data before)
               data)]
    (reduce
     (fn [data field]
       (let [specs (some-> (item-map-form (:schema rdef) field)
                           labeled-ref-specs)]
         (if (empty? specs)
           data
           (let [prev (vec (get before field))]
             (update data field
                     (fn [items]
                       (vec (map-indexed
                             (fn [i item]
                               (label-pass engine tx specs item (get prev i)))
                             items))))))))
     data
     (schema/entry-keys (:schema rdef)))))

(defn accepts?
  "Does a document's :accept list take this media type? A `type/*`
  entry takes the whole family; no list takes every type."
  [accept media-type]
  (or (empty? accept)
      (let [mt (some-> media-type str (str/split #";") first str/trim
                       str/lower-case)]
        (boolean
         (some (fn [a]
                 (let [a (str/lower-case a)]
                   (if (str/ends-with? a "/*")
                     (and mt (str/starts-with? mt (subs a 0 (dec (count a)))))
                     (= a mt))))
               accept)))))

(defn refused-documents
  "{field [why]} for every :waymark/document entry (docs/spec-documents.md
  §2) holding an attachment this write sets whose media_type its
  :accept does not take — each why names the field, the file and the
  types it takes; nil when all are taken. An id the row already held
  is not read again, and an attachment that is not there is the
  dangling-ref wall's to name. With a nil `before` every attachment
  is read: the law sweep asks it so of stored rows, to name the ones a
  tightened :accept left behind (server/law-sweep)."
  [engine tx rdef data before]
  (let [ids #(let [v (get %1 %2)]
               (if (sequential? v) v (some-> v vector)))]
    (not-empty
     (into {}
           (keep (fn [[f {:keys [properties]}]]
                   (when-some [accept (seq (get-in properties
                                                   [:waymark/document :accept]))]
                     (let [had (set (map str (ids before f)))
                           whys (for [id (ids data f)
                                      :when (not (had (str id)))
                                      :let [a (target-of engine tx :attachment id)
                                            mt (get-in a [:data :media_type])]
                                      :when (and a (not (accepts? accept mt)))]
                                  (str (name f) " takes " (str/join ", " accept)
                                       "; " (get-in a [:data :name]) " is " mt))]
                       (when (seq whys) [f (vec whys)])))))
           (schema/entry-map (:schema rdef))))))

(defn labels-and-groups
  "The two engine passes every write's data goes through after the
  handler: ref labels first, then one-of enforcement — so a cleared
  arm clears its label with it."
  [engine tx rdef data before]
  (-> (maintain-ref-labels engine tx rdef data before)
      (as-> d (groups/enforce rdef d before))))
