(ns waymark10.checks-assembly
  "The assembly-time gate: cross-kind checks over the registry, run
  once every kind is known — ported by name from waymark9
  core/checks.py's registry battery (check_refs, check_owns,
  check_related, check_derived_cycles). A declaration is judged alone
  at import (waymark10.checks); an EDGE between kinds can only be
  judged here, where the other end exists. Separate namespace on
  purpose: these run at assembly, never per-declaration.

  run-all takes the registry map ({:kinds {kind rdef}}) and returns
  {:warnings [str …]} or throws the named check's definition error
  (ex-data carries {:check <name>}). Warnings are \"[check-name] …\"
  strings the registry gate surfaces on *err*.

  Assembly punts, each a named comment at its site: owns seeds and
  predecessor refs (declarations unported), the cross-kind fact DAG
  walk (check-derived-cycles admits :count edges — phase 6 — but does
  not yet chase cycles through them), and check-touches /
  check-compounds — :touches and compound-input declarations have no
  v10 spelling yet; each check arrives with its feature."
  (:require [clojure.string :as str]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- err [kind check msg]
  (throw (t/definition-error
          (str (name kind) " [" (name check) "] " msg)
          {:check check})))

;; ── shared introspection ────────────────────────────────────────────

(defn- unwrap-maybe [s]
  (if (and (vector? s) (= :maybe (first s))) (second s) s))

(defn- head
  "The head type of an entry's schema form, optionality unwrapped:
  [:maybe :waymark/ref] → :waymark/ref, [:string {:max 1}] → :string."
  [s]
  (let [s (unwrap-maybe s)]
    (if (vector? s) (first s) s)))

(defn- item-map-form
  "The [:map …] item form of a vector-of-map field, nil otherwise."
  [form k]
  (when-some [s (schema/field-schema form k)]
    (when (and (vector? s) (= :vector (first s)))
      (let [item (last s)]
        (when (and (vector? item) (= :map (first item)))
          item)))))

(defn- surfaces
  "Every :map surface of a kind the ref rules walk: the data schema,
  each vector-of-map item, each action input."
  [r]
  (concat [["data" (:schema r)]]
          (keep (fn [k]
                  (when-some [item (item-map-form (:schema r) k)]
                    [(str "data." (name k) "[]") item]))
                (schema/entry-keys (:schema r)))
          (keep (fn [[aname a]]
                  (when (:input a)
                    [(str "input " (name aname)) (:input a)]))
                (sort-by key (:actions r)))))

(defn- promoted-fields
  "Filterable-or-sortable fields — the promoted columns storage can
  index, the only columns a cross-kind query is allowed to lean on."
  [r]
  (into (set (keys (:filterable r)))
        (get-in r [:sortable :fields])))

;; ── check-refs ──────────────────────────────────────────────────────

(defn- id-target
  "The kind a field named <kind>_id points at by convention, nil
  otherwise."
  [f]
  (let [n (name f)]
    (when (and (str/ends-with? n "_id") (< 3 (count n)))
      (keyword (subs n 0 (- (count n) 3))))))

(defn- check-refs
  "Assembly-time reference checks (waymark9 check_refs, design §2).
  Errors: a :waymark/ref naming an unregistered kind is a broken
  declaration. Warnings: a <kind>_id-named field for a registered kind
  without the ref type is the v1 heuristic demoted to a lint — it
  tells you to use the declaration that retires it."
  [reg]
  (let [kinds (set (keys (:kinds reg)))]
    (into []
          (mapcat
           (fn [[kind r]]
             (for [[where form] (surfaces r)
                   :let [entries (schema/entry-map form)]
                   f (schema/entry-keys form)
                   :let [{props :properties s :schema} (get entries f)
                         warning
                         (if (= :waymark/ref (head s))
                           (let [target (:kind props)]
                             (when-not target
                               (err kind :refs
                                    (str where "." (name f) " is a :waymark/ref "
                                         "that names no :kind — the ref's target "
                                         "is a declaration, not a guess")))
                             (when-not (contains? kinds target)
                               (err kind :refs
                                    (str where "." (name f) ": Ref names kind "
                                         target ", which is not registered on "
                                         "this engine")))
                             ;; punt: predecessor refs (waymark9
                             ;; _check_predecessor, design E7) — the
                             ;; :predecessor entry property has no v10
                             ;; resolver yet; its promoted order/partition
                             ;; rules arrive with it.
                             nil)
                           (let [target (id-target f)
                                 xd (:x-display props)]
                             (when (and target
                                        (contains? kinds target)
                                        (not (or (:widget xd) (:hidden xd)
                                                 (:raw xd))))
                               (str "[refs] " (name kind) " " where "." (name f)
                                    " names a " target " by convention but is "
                                    "not typed :waymark/ref — declare the ref "
                                    "so the picker, the navigable reference, "
                                    "and the dangling-ref check come from one "
                                    "declaration"))))]
                   :when warning]
               warning)))
          (sort-by key (:kinds reg)))))

;; ── check-owns ──────────────────────────────────────────────────────

(defn- check-owns
  "Ownership edges (waymark9 check_owns, design E4) validate at
  assembly, where every kind is known: the child exists, :via is a ref
  back at the parent on an eq-filterable (promoted) column, cascade
  endpoints are real actions the runner can drive body-less and
  etag-less, and rollups name filterable child fields without shadowing
  a parent query param."
  [reg]
  (doseq [[kind r] (sort-by key (:kinds reg))
          edge (:owns r)]
    (let [ck (:kind edge)
          child (get-in reg [:kinds ck])]
      (when-not child
        (err kind :owns (str "owns: child kind " ck
                             " is not registered on this engine")))
      (let [via (:via edge)
            centry (get (schema/entry-map (:schema child)) via)]
        (when-not centry
          (err kind :owns (str "owns(" ck "): via " via
                               " is not a field of the child's data schema")))
        (when-not (and (= :waymark/ref (head (:schema centry)))
                       (= kind (get-in centry [:properties :kind])))
          (err kind :owns (str "owns(" ck "): via " via " must be a Ref back "
                               "at the parent — a :waymark/ref whose :kind is "
                               kind)))
        (when-not (contains? (set (get (:filterable child) via)) :eq)
          (err kind :owns (str "owns(" ck "): via " via " must be "
                               ":eq-filterable on the child — the cascade "
                               "query and rollup GROUP BY run on the promoted "
                               "column"))))
      (doseq [[parent-action child-action] (sort-by key (:on edge))]
        (when-not (contains? (:actions r) parent-action)
          (err kind :owns (str "owns(" ck "): cascade key " parent-action
                               " is not an action of " kind)))
        (let [target (get (:actions child) child-action)]
          (when-not target
            (err kind :owns (str "owns(" ck "): cascade target " child-action
                                 " is not an action of " ck)))
          (when (:input target)
            (err kind :owns (str "owns(" ck "): cascade target " child-action
                                 " takes input — the cascade runner sends no "
                                 "body")))
          (when (get-in target [:safety :fence])
            (err kind :owns (str "owns(" ck "): cascade target " child-action
                                 " is fenced — the cascade runner holds no "
                                 "etag")))))
      (let [child-filterable (set (keys (:filterable child)))
            child-promoted (promoted-fields child)
            parent-params (-> #{:state :sort}
                              (into (keys (:filterable r)))
                              (into (get-in r [:sortable :fields])))]
        (doseq [[rname rollup] (sort-by key (:rollups edge))]
          (when (contains? parent-params rname)
            ;; rollups become the parent collection's query params; a
            ;; name collision would shadow a real filter
            (err kind :owns (str "owns(" ck ") rollup " rname " collides "
                                 "with a parent filter/sort name")))
          (doseq [f (sort (keys (:where rollup)))]
            (when-not (or (= :state f) (contains? child-filterable f))
              (err kind :owns (str "owns(" ck ") rollup " rname ": where "
                                   "field " f " is not filterable on the "
                                   "child — the rollup query runs on "
                                   "promoted columns"))))
          (when (= :sum (:agg rollup))
            (let [of (:of rollup)]
              (when-not (contains? (set (schema/entry-keys (:schema child))) of)
                (err kind :owns (str "owns(" ck ") rollup " rname ": of " of
                                     " is not a child data field")))
              (when-not (contains? child-promoted of)
                (err kind :owns (str "owns(" ck ") rollup " rname ": of " of
                                     " must be filterable or sortable — the "
                                     "SUM runs on the promoted column")))))))
      ;; punt: owns seeds (waymark9 _check_seed, design E4) — the :seed
      ;; declaration has no v10 spelling yet; its source-kind / where /
      ;; copy / defaults rules arrive with it.
      )))

;; ── check-related ───────────────────────────────────────────────────

(def ^:private join-ops #{:< :<= := :>= :>})

(def ^:private ordered-families #{:number :date})

(defn- join-family
  "The comparison family of one join field's column: :date / :number /
  :boolean / :string / :array — or :missing when there is no such data
  field. Derived from the field's schema head (waymark9 _join_family;
  v10 has no date-time type yet, so :waymark/date is the only temporal
  family)."
  [r f]
  (let [s (schema/field-schema (:schema r) f)]
    (cond
      (nil? s) :missing
      (and (vector? s) (= :vector (first s))) :array
      :else (let [h (if (vector? s) (first s) s)]
              (cond
                (= :waymark/date h) :date
                (contains? #{:int :double :decimal} h) :number
                (= :boolean h) :boolean
                :else :string)))))

(defn- join-side
  "The family of one join side, held to §1's admission rules: a real
  data field, promoted (the recompute query must be indexable), and
  scalar — no scalar join over an array."
  [kind where side f r rkind]
  (let [fam (join-family r f)]
    (when (= :missing fam)
      (err kind :related (str where ": join field " f " (" side ") is not a "
                              "data field of " rkind)))
    (when-not (contains? (promoted-fields r) f)
      (err kind :related (str where ": join field " f " (" side ") is not a "
                              "promoted (filterable or sortable) column on "
                              rkind " — a predicate the storage layer cannot "
                              "index is a predicate the maintainer cannot "
                              "honor")))
    (when (= :array fam)
      (err kind :related (str where ": join field " f " (" side ") is an "
                              "array column — no scalar join over an array")))
    fam))

(defn- check-related-edge
  "One related edge, held to §1's admission rules: the target is
  registered, both join fields are promoted columns on their own sides,
  and the operator is one those column types can serve."
  [reg kind r where {target-kind :kind on :on}]
  (let [target (get-in reg [:kinds target-kind])]
    (when-not target
      (err kind :related (str where ": target kind " target-kind
                              " is not registered on this engine")))
    (doseq [[ours op theirs] on]
      (when-not (contains? join-ops op)
        (err kind :related (str where ": op " op
                                " is not one of :< :<= := :>= :>")))
      (if (= :id theirs)
        ;; identity join: the target's primary key IS the indexed
        ;; column; the ours side still answers to the promotion rules,
        ;; because the inverted map is a point lookup on OUR column.
        (do
          (when-not (= := op)
            (err kind :related (str where ": an identity join is an equality "
                                    "— op " op " against :id")))
          (let [fam (join-side kind where "ours" ours r kind)]
            (when-not (= :string fam)
              (err kind :related (str where ": identity join across "
                                      "mismatched column types (" ours " is "
                                      fam ", :id is :string) — join an id "
                                      "against a string (ideally "
                                      ":waymark/ref) column")))))
        (let [ofam (join-side kind where "ours" ours r kind)
              tfam (join-side kind where "theirs" theirs target target-kind)]
          (if (= := op)
            (when-not (= ofam tfam)
              (err kind :related (str where ": equality join across "
                                      "mismatched column types (" ours " is "
                                      ofam ", " theirs " is " tfam ")")))
            (when (or (not (contains? ordered-families ofam))
                      (not= ofam tfam))
              (err kind :related (str where ": op " op " cannot be served by "
                                      "the promoted column types (" ours
                                      " is " ofam ", " theirs " is " tfam
                                      ") — ordered joins need matching "
                                      "numeric or temporal columns on both "
                                      "sides")))))))))

(defn- check-related
  "Related edges (waymark9 check_related, design 6.0 §1) validate at
  assembly: every declared edge is held to the admission rules; a link
  citing an edge must compile onto the public range grammar
  (_gte/_lte — strict comparisons have no query parameter,
  deliberately) and cannot ride an identity join."
  [reg]
  (doseq [[kind r] (sort-by key (:kinds reg))]
    (doseq [[ename edge] (sort-by key (:related r))]
      (check-related-edge reg kind r (str "related " ename) edge))
    (doseq [ld (:links r)
            :when (:edge ld)]
      (let [edge (get (:related r) (:edge ld))]
        (when-not edge
          (err kind :related (str "link " (:rel ld) ": edge " (:edge ld)
                                  " names no :related entry of this kind")))
        (doseq [[_ op theirs] (:on edge)]
          (when (contains? #{:< :>} op)
            (err kind :related (str "link " (:rel ld) ": op " op " has no "
                                    "query parameter — the compiled href "
                                    "speaks the public range grammar "
                                    "(_gte/_lte); use :<=, :>=, or :=")))
          (when (= :id theirs)
            (err kind :related (str "link " (:rel ld) ": :theirs :id has no "
                                    "collection query parameter — the ref "
                                    "field itself already renders the "
                                    "navigable reference to the parent"))))))))

;; ── check-derived-cycles ────────────────────────────────────────────

(defn- check-count-edge
  "One count spec's assembly half (phase 6): its declared edge exists
  on this kind, and every :where field is promoted on the target (or
  :state, which is always its own indexed column) — a predicate the
  maintainer's COUNT cannot run on an indexed column is a predicate it
  cannot honor."
  [reg kind r fact c]
  (let [target-kind
        (if-some [ek (:related c)]
          (or (:kind (get (:related r) ek))
              (err kind :derived-cycles
                   (str "derived field " fact ": count reads related edge "
                        ek ", which this kind never declares")))
          (let [ck (:owns c)]
            (when-not (some #(= ck (:kind %)) (:owns r))
              (err kind :derived-cycles
                   (str "derived field " fact ": count reads owned kind "
                        ck ", which this kind never owns")))
            ck))
        target (get-in reg [:kinds target-kind])]
    (doseq [f (sort (keys (:where c)))]
      (when-not (or (= :state f) (contains? (promoted-fields target) f))
        (err kind :derived-cycles
             (str "derived field " fact ": count where field " f " is not "
                  "promoted (filterable or sortable) on " target-kind
                  " — the maintainer's COUNT runs on indexed columns"))))))

(defn- check-derived-cycles
  "Cross-kind derived cycles (waymark9 check_derived_cycles): v10's
  cross-kind door is the phase-6 count spec — {:count {:related/:owns
  …}} reads through a declared, admission-checked edge, validated here
  where the other end exists. Everything else stays shut: an :over
  entry that is neither :now nor an own data field is refused by name
  (a count fact IS an own data field, so composition costs nothing).

  ;; punt: the cross-kind fact DAG walk (waymark9's kind.field node
  ;; graph) — count-where over another kind's derived fact can close a
  ;; cross-kind cycle this check does not see; the maintainer's
  ;; recompute terminates on a per-write visited set instead, and the
  ;; assembly-time DAG check arrives when a declaration demands it."
  [reg]
  (doseq [[kind r] (sort-by key (:kinds reg))
          :let [dkeys (set (schema/entry-keys (:schema r)))]
          [fact d] (sort-by key (:derived r))]
    (doseq [o (:over d)]
      (when-not (or (= :now o) (contains? dkeys o))
        (err kind :derived-cycles
             (str "derived field " fact ": cross-kind derived inputs read "
                  "through count edges only; over= names " o " — neither "
                  ":now nor a data field of " kind))))
    (when-some [c (:count d)]
      (check-count-edge reg kind r fact c))))

;; ── the battery ─────────────────────────────────────────────────────

;; punt: check-touches (declared cross-kind write sets) and
;; check-compounds (compound inputs) — their declaration shapes are
;; unported; each check arrives with its feature.

(defn run-all
  "The assembly battery in waymark9 order: refs, owns, related,
  derived-cycles. Throws the first error, returns {:warnings [str …]}."
  [reg]
  {:warnings
   (into []
         (mapcat #(% reg))
         [check-refs check-owns check-related check-derived-cycles])})
