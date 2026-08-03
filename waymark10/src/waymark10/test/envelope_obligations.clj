(ns waymark10.test.envelope-obligations
  "Batch-A envelope obligations — the parts/links/depth/effort
  honesty battery, shaped like waymark10.test.conformance (which the
  maintainer folds this namespace into): pure fns over one PARSED
  wire document (wire/read-json output — keyword keys) return seqs of
  violation STRINGS, empty on conformance; wire halves take the
  caller's transport as a callback, never a live engine.

  THE FOLD-IN the maintainer should perform:
  1. conformance/envelope-keys gains :parts as an OPTIONAL reserved
     key — the shape check becomes: keys minus #{:parts} = the
     reserved ten, i.e.
       (not= envelope-keys (disj (set (keys env)) :parts))
     (parts renders only when a placed action is available, so it is
     present-or-absent, never empty).
  2. The public fns below move into conformance.clj verbatim (they
     already share its conventions); their deftest callers keep
     working through the new home.

  The parts shape being enforced (recorded in render.clj's ns
  docstring): parts is a REFINEMENT — a placed action renders
  top-level AND per item; the exclusive shape was rejected because
  depth=summary drops parts and would conceal live affordances."
  (:require [clojure.string :as str]
            [waymark10.demand :as demand]
            [waymark10.server.collections :as collections]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]))

(set! *warn-on-reflection* true)

(defn wire-name [k] (keyword (p/wire-key k)))

(defn- where-of [env]
  (str (:kind env) "@" (:state env) " " (:self env)))

;; ── parts ───────────────────────────────────────────────────────────

(defn- placed-actions
  "wire action name → {:path wire-kw :key wire-kw} for the declared
  placed actions of one rdef."
  [rdef]
  (into {}
        (keep (fn [[aname a]]
                (when-some [scope (get-in rdef [:part-scopes (:place a)])]
                  [(wire-name aname) {:path (wire-name (:path scope))
                                      :key (wire-name (:key scope))}])))
        (:actions rdef)))

(defn parts-violations
  "The parts-shape obligation for one full GET envelope:
  - every parts group belongs to a declared part scope and names the
    declared key;
  - the group mirrors its data array (same length, same key values,
    :item equal to the data item);
  - every part action is a declared placed action of that scope, ALSO
    advertised top-level (refinement, never a replacement), with the
    key field const-bound to the item's key and a demand class on the
    entry;
  - every advertised placed action with a non-empty scope array
    re-renders in parts;
  - per-item narrations carry a reason sentence."
  [rdef env]
  (let [where (where-of env)
        placed (placed-actions rdef)
        scope-of (into {} (map (fn [[_ s]] [(:path s) s])) placed)
        advertised (set (keys (:actions env)))]
    (concat
     ;; every group is declared, keyed right, and mirrors the data
     (mapcat
      (fn [[path group]]
        (let [scope (get scope-of path)
              data-items (vec (get-in env [:data path]))
              items (vec (:items group))]
          (concat
           (when-not scope
             [(str where ": parts group " (name path)
                   " names no declared part scope")])
           (when (and scope (not= (name (:key scope)) (str (:key group))))
             [(str where ": parts." (name path) " key " (pr-str (:key group))
                   " is not the declared " (name (:key scope)))])
           (when (not= (count data-items) (count items))
             [(str where ": parts." (name path) " has " (count items)
                   " items for " (count data-items) " data rows — the group"
                   " mirrors the array")])
           (mapcat
            (fn [idx item]
              (let [data-item (get data-items idx)
                    kf (:key scope)
                    where' (str where " parts." (name path)
                                "[" idx "]")]
                (concat
                 (when (and scope
                            (not= (str (:key item))
                                  (str (get data-item kf))))
                   [(str where' ": key " (pr-str (:key item))
                         " is not the data row's " (name kf) " "
                         (pr-str (get data-item kf)))])
                 (when (not= (:item item) data-item)
                   [(str where' ": item payload diverges from data."
                         (name path) "[" idx "]")])
                 (mapcat
                  (fn [[aname entry]]
                    (let [scope' (get placed aname)
                          const (get-in entry [:input :properties
                                               (when scope' (:key scope'))
                                               :const])]
                      (concat
                       (when-not scope'
                         [(str where' " actions." (name aname)
                               ": not a declared placed action")])
                       (when (and scope' (not= (:path scope') path))
                         [(str where' " actions." (name aname)
                               ": placed on " (name (:path scope'))
                               ", rendered under " (name path))])
                       (when-not (contains? advertised aname)
                         [(str where' " actions." (name aname)
                               ": in parts but NOT top-level — parts is a"
                               " refinement, never a replacement")])
                       (when (and scope' (not= (str const) (str (:key item))))
                         [(str where' " actions." (name aname)
                               ": the key field's const " (pr-str const)
                               " is not the item key " (pr-str (:key item)))])
                       (when-not (contains? (set demand/classes)
                                            (:effort entry))
                         [(str where' " actions." (name aname)
                               ": effort " (pr-str (:effort entry))
                               " is not a demand class")]))))
                  (:actions item))
                 (for [[aname entry] (:unavailable item)
                       :when (or (not (string? (:reason entry)))
                                 (str/blank? (:reason entry)))]
                   (str where' " unavailable." (name aname)
                        ": reason " (pr-str (:reason entry))
                        " is missing or blank")))))
            (range) items))))
      (:parts env))
     ;; every advertised placed action over a non-empty array re-renders
     (for [[aname scope] placed
           :when (and (contains? advertised aname)
                      (seq (get-in env [:data (:path scope)]))
                      (not (get-in env [:parts (:path scope)])))]
       (str where ": placed action " (name aname) " is advertised over "
            (count (get-in env [:data (:path scope)])) " data rows but"
            " renders no parts group")))))

(defn parts-enforcement-violations
  "The parts-honesty wire half: every part item's action, dry-run
  invoked with the pre-bound key (plus the caller's :fill for the
  other required fields), is ACCEPTED; every per-item narration,
  dry-run invoked the same way, refuses 409 with the narrated reason
  as its detail — advertisement equals enforcement, per item.

  opts: :post (fn [href body] → {:status int :body parsed}) — the
  caller's transport, dry_run applied here; :fill (fn [action-kw
  item] → input map for the OTHER fields), defaults to {}."
  [env {:keys [post fill]}]
  (let [fill (or fill (constantly {}))
        where (where-of env)
        dry (fn [href body] (post (str href "?dry_run=1") body))]
    (mapcat
     (fn [[path group]]
       (mapcat
        (fn [idx item]
          (let [kf (keyword (str (:key group)))
                where' (str where " parts." (name path) "[" idx "]")]
            (concat
             (mapcat
              (fn [[aname entry]]
                (let [input (merge (fill aname item)
                                   {kf (get-in entry [:input :properties
                                                      kf :const])})
                      {:keys [status body]} (dry (:href entry) input)]
                  (when-not (and (= 200 status) (true? (:valid body)))
                    [(str where' " actions." (name aname)
                          ": dry-run with the pre-bound key answered "
                          status " " (pr-str (or (:detail body)
                                                 (:errors body)))
                          " — the part advertised what enforcement refuses")])))
              (:actions item))
             (mapcat
              (fn [[aname entry]]
                (let [href (str (:self env) "/-/" (name aname))
                      input (merge (fill aname item) {kf (:key item)})
                      {:keys [status body]} (dry href input)]
                  (concat
                   (when (and (= 200 status) (true? (:valid body)))
                     [(str where' " unavailable." (name aname)
                           ": narrated per item yet dry-run ACCEPTS the"
                           " item key " (pr-str (:key item)))])
                   (when (and (= 409 status)
                              (not= (:detail body) (:reason entry)))
                     [(str where' " unavailable." (name aname)
                           ": narrated " (pr-str (:reason entry))
                           " but enforcement said "
                           (pr-str (:detail body)))]))))
              (:unavailable item)))))
        (range) (:items group)))
     (:parts env))))

;; ── links ───────────────────────────────────────────────────────────

(defn- effective-limit
  "A link's own :limit if declared, else the framework default — every
  :embed link is grid mode now, no bool-form exemption."
  [ld]
  (or (:limit (when (map? (:embed ld)) (:embed ld)))
      collections/page-size-default))

(defn links-violations
  "The links-shape obligation for one envelope: every rendered rel is
  declared, carries an href, and its badge equals the envelope's OWN
  data value for the declared badge field (the no-N+1 rule made
  checkable: scent comes from materialized facts, so the envelope
  already carries the truth). Every embed is grid mode: embedded
  inlines are envelope-minus-data items within the link's own
  effective limit, and total/page are present whenever embedded is."
  [rdef env]
  (let [where (where-of env)
        declared (into {} (map (juxt (comp wire-name :rel) identity))
                       (:links rdef))]
    (mapcat
     (fn [[rel link]]
       (let [ld (get declared rel)]
         (concat
          (when-not ld
            [(str where " links." (name rel) ": not a declared link")])
          (when-not (and (string? (:href link))
                         (not (str/blank? (:href link))))
            [(str where " links." (name rel) ": href "
                  (pr-str (:href link)) " is missing or blank")])
          (when-some [badge-field (:badge ld)]
            (let [data-value (get-in env [:data (wire-name badge-field)])]
              (cond
                (and (some? data-value)
                     (not= (str (:badge link)) (str data-value)))
                [(str where " links." (name rel) ": badge "
                      (pr-str (:badge link)) " ≠ data."
                      (name badge-field) " " (pr-str data-value))]

                (and (nil? data-value) (contains? link :badge))
                [(str where " links." (name rel) ": badge "
                      (pr-str (:badge link))
                      " rides a null fact — absence, not zero")])))
          (when (:embed ld)
            (concat
             (when-not (and (contains? link :total) (contains? link :page))
               [(str where " links." (name rel)
                     ": an :embed link carries no total/page — every "
                     "embed is grid mode now")])
             (when-some [embedded (:embedded link)]
               (let [limit (effective-limit ld)]
                 (concat
                  (when (< limit (count embedded))
                    [(str where " links." (name rel) ": " (count embedded)
                          " embedded items exceed the effective limit " limit)])
                  (for [item embedded
                        :when (contains? item :data)]
                    (str where " links." (name rel)
                         ": an embedded item carries data — inlines are"
                         " envelope-minus-data"))))))))))
     (:links env))))

(defn links-wire-violations
  "The links-honesty wire half: every rendered href GETs 200 for the
  acting principal. get-fn: (fn [href] → {:status int :body parsed})."
  [env get-fn]
  (let [where (where-of env)]
    (mapcat
     (fn [[rel link]]
       (let [{:keys [status]} (get-fn (:href link))]
         (when (not= 200 status)
           [(str where " links." (name rel) ": GET " (:href link)
                 " answered " status " — a link that does not resolve"
                 " for its reader is a lie")])))
     (:links env))))

;; ── depth ───────────────────────────────────────────────────────────

(defn fields-violations
  "The grid-column obligation for one envelope (full or summary
  alike — :fields is always present at both depths, unlike :data):
  every key rides render/grid-fields' own rule (no :vector, no prose
  unless teaser-flagged — the SAME fn envelope itself calls, so this
  checks the rule was actually applied, not a second copy of it that
  could drift), and :fields is never absent."
  [rdef env]
  (let [where (where-of env)
        eligible (render/grid-fields rdef)]
    (cond-> []
      (not (contains? env :fields))
      (conj (str where ": no :fields — every depth carries a grid projection"))

      (seq (remove eligible (keys (:fields env))))
      (conj (str where ": fields " (vec (remove eligible (keys (:fields env))))
                 " are not grid-eligible (vector) per "
                 (name (:kind rdef)) "'s own declaration")))))

(defn depth-violations
  "The depth contract over one row read twice: the summary is the
  full envelope minus data and parts — same identity, same COMPLETE
  action partition (the refinement shape is what keeps a summary
  honest), and the SAME :fields (both depths project from the same
  :data, so they must agree byte-for-byte)."
  [{:keys [full summary]}]
  (let [where (where-of full)]
    (cond-> []
      (contains? summary :data)
      (conj (str where ": depth=summary still carries data"))

      (contains? summary :parts)
      (conj (str where ": depth=summary still carries parts"))

      (not= (:fields full) (:fields summary))
      (conj (str where ": summary depth changed :fields "
                 (pr-str (:fields summary)) " ≠ " (pr-str (:fields full))))

      (not= (select-keys full [:kind :self :state :summary])
            (select-keys summary [:kind :self :state :summary]))
      (conj (str where ": summary depth changed the envelope's identity"))

      (not= (set (keys (:actions full))) (set (keys (:actions summary))))
      (conj (str where ": summary depth changed the advertised actions "
                 (vec (sort (keys (:actions summary)))) " ≠ "
                 (vec (sort (keys (:actions full))))))

      (not= (set (keys (:unavailable full)))
            (set (keys (:unavailable summary))))
      (conj (str where ": summary depth changed the narrated unavailable")))))

(defn rows-none-violations
  "The rows=none contract for one collection item: actions AND
  unavailable are null — explicitly unknown, never {} — and the
  follow-up GET of the item's self answers 200 with a real actions
  map. get-fn: (fn [href] → {:status int :body parsed})."
  [item get-fn]
  (let [where (where-of item)
        {:keys [status body]} (get-fn (:self item))]
    (cond-> []
      (not (and (contains? item :actions) (nil? (:actions item))))
      (conj (str where ": rows=none item actions "
                 (pr-str (:actions item)) " is not an explicit null"))

      (not (and (contains? item :unavailable) (nil? (:unavailable item))))
      (conj (str where ": rows=none item unavailable "
                 (pr-str (:unavailable item)) " is not an explicit null"))

      (not= 200 status)
      (conj (str where ": the follow-up GET answered " status))

      (and (= 200 status) (not (map? (:actions body))))
      (conj (str where ": the follow-up GET carries no actions map —"
                 " unknown never resolved")))))

;; ── effort ──────────────────────────────────────────────────────────

(defn- prose-prop? [prop]
  (= "prose" (get-in prop [:x-display :widget])))

(defn- entry-effort-violations [where aname entry]
  (let [effort (:effort entry)
        props (get-in entry [:input :properties])
        required (into #{} (map keyword) (get-in entry [:input :required]))
        demanding (into {}
                        (remove (fn [[_f prop]]
                                  (contains? prop :const)))
                        props)]
    (cond-> []
      (not (contains? (set demand/classes) effort))
      (conj (str where " actions." (name aname) ": effort "
                 (pr-str effort) " is not one of " demand/classes))

      (and (= "assent" effort)
           (seq (filter #(contains? demanding %) required)))
      (conj (str where " actions." (name aname) ": effort assent with"
                 " required un-bound field(s) "
                 (vec (sort (filter #(contains? demanding %) required)))
                 " — assent is one click"))

      (and (= "composition" effort)
           (not (some prose-prop? (vals demanding))))
      (conj (str where " actions." (name aname) ": effort composition"
                 " without a prose field — nothing unbounded is asked")))))

(defn effort-violations
  "The effort-truth obligation over one envelope: every action entry
  — top-level and part-bound — carries a demand class; assent entries
  demand no required un-bound input; composition entries carry a
  prose field."
  [env]
  (let [where (where-of env)]
    (concat
     (mapcat (fn [[aname entry]]
               (entry-effort-violations where aname entry))
             (:actions env))
     (mapcat
      (fn [[path group]]
        (mapcat
         (fn [idx item]
           (mapcat (fn [[aname entry]]
                     (entry-effort-violations
                      (str where " parts." (name path) "[" idx "]")
                      aname entry))
                   (:actions item)))
         (range) (:items group)))
      (:parts env)))))
