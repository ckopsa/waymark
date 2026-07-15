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

(defn- aggregate-target
  "The kind an aggregate spec reads, resolved through its declared
  edge — or the named refusal when the edge does not exist here."
  [_reg kind r fact an c]
  (if-some [ek (:related c)]
    (or (:kind (get (:related r) ek))
        (err kind :derived-cycles
             (str "derived field " fact ": " an " reads related edge "
                  ek ", which this kind never declares")))
    (let [ck (:owns c)]
      (when-not (some #(= ck (:kind %)) (:owns r))
        (err kind :derived-cycles
             (str "derived field " fact ": " an " reads owned kind "
                  ck ", which this kind never owns")))
      ck)))

(defn- check-aggregate-edge
  "One aggregate spec's assembly half (phase 6's count check, batch C
  widens it to :sum): its declared edge exists on this kind, every
  :where field is promoted on the target (or :state, which is always
  its own indexed column), and a sum's :of is a promoted target data
  field — a column the maintainer's COUNT/SUM cannot run indexed is a
  predicate it cannot honor (the rollup :sum precedent, verbatim)."
  [reg kind r fact an c]
  (let [target-kind (aggregate-target reg kind r fact an c)
        target (get-in reg [:kinds target-kind])]
    (doseq [f (sort (keys (:where c)))]
      (when-not (or (= :state f) (contains? (promoted-fields target) f))
        (err kind :derived-cycles
             (str "derived field " fact ": " an " where field " f " is not "
                  "promoted (filterable or sortable) on " target-kind
                  " — the maintainer's aggregate runs on indexed columns"))))
    (when-some [of (:of c)]
      (when-not (contains? (set (schema/entry-keys (:schema target))) of)
        (err kind :derived-cycles
             (str "derived field " fact ": sum of " of " is not a data "
                  "field of " target-kind)))
      (when-not (contains? (promoted-fields target) of)
        (err kind :derived-cycles
             (str "derived field " fact ": sum of " of " must be filterable "
                  "or sortable on " target-kind " — the SUM runs on the "
                  "promoted column")))
      (let [fact-head (head (schema/field-schema (:schema r) fact))
            of-head (head (schema/field-schema (:schema target) of))]
        (when-not (contains? #{:int :decimal :double} of-head)
          (err kind :derived-cycles
               (str "derived field " fact ": sum of " of " is not numeric "
                    "on " target-kind)))
        (when (and (= :int fact-head) (not= :int of-head))
          (err kind :derived-cycles
               (str "derived field " fact " is declared :int but sums "
                    target-kind "." (name of) " (" of-head ") — an integer "
                    "fact cannot hold a fractional sum; declare it "
                    ":decimal")))))))

;; ── the cross-kind fact DAG (batch C, closing the phase-6 punt) ─────

(defn- fact-edges
  "The kind.fact nodes one fact depends on: same-kind :over facts, and
  — through an aggregate's declared edge — the target facts its
  :where filters and :of read. The edges are the whole cross-kind
  door, so this graph IS waymark9's kind.field node graph."
  [reg kind r fact d]
  (let [own-facts (set (keys (:derived r)))
        over-deps (for [o (:over d)
                        :when (contains? own-facts o)]
                    [kind o])
        agg (or (:count d) (:sum d))
        agg-deps (when agg
                   (let [tk (aggregate-target
                             reg kind r fact
                             (if (:count d) "count" "sum") agg)
                         t (get-in reg [:kinds tk])
                         tfacts (set (keys (:derived t)))]
                     (for [f (concat (keys (:where agg))
                                     (when-some [of (:of agg)] [of]))
                           :when (contains? tfacts f)]
                       [tk f])))]
    (concat over-deps agg-deps)))

(defn- check-fact-dag
  "Walk the full cross-kind fact graph, refusing cycles by their
  kind.fact path — an aggregate whose where/of reads a target fact
  that (transitively) aggregates back closes a loop no single kind's
  own cycle check can see; the maintainer's per-write visited set
  would silently truncate it, so assembly refuses it instead."
  [reg]
  (let [nodes (for [[kind r] (sort-by key (:kinds reg))
                    [fact _] (sort-by key (:derived r))]
                [kind fact])
        edges (into {}
                    (map (fn [[kind fact]]
                           (let [r (get-in reg [:kinds kind])]
                             [[kind fact]
                              (vec (fact-edges reg kind r fact
                                               (get-in r [:derived fact])))])))
                    nodes)
        path-str (fn [cycle]
                   (str/join " → " (map (fn [[k f]]
                                          (str (name k) "." (name f)))
                                        cycle)))]
    (letfn [(visit [n stack on-stack done]
              (cond
                (contains? @done n) nil
                (contains? on-stack n)
                (conj (vec (drop-while #(not= % n) stack)) n)
                :else
                (or (some #(visit % (conj stack n) (conj on-stack n) done)
                          (get edges n))
                    (do (swap! done conj n) nil))))]
      (let [done (atom #{})]
        (doseq [n nodes]
          (when-some [cycle (visit n [] #{} done)]
            (err (ffirst cycle) :derived-cycles
                 (str "cross-kind derived facts form a cycle: "
                      (path-str cycle)
                      " — a fact defined in terms of itself defines "
                      "nothing"))))))))

(defn- check-derived-cycles
  "Cross-kind derived cycles (waymark9 check_derived_cycles): v10's
  cross-kind door is the aggregate spec — {:count|:sum
  {:related/:owns …}} reads through a declared, admission-checked
  edge, validated here where the other end exists. Everything else
  stays shut: an :over entry that is neither :now nor an own data
  field is refused by name (an aggregate fact IS an own data field,
  so composition costs nothing). Batch C closes the phase-6 punt: the
  full cross-kind fact DAG walks here, refusing cycles by their
  kind.fact path."
  [reg]
  (doseq [[kind r] (sort-by key (:kinds reg))
          :let [dkeys (set (schema/entry-keys (:schema r)))]
          [fact d] (sort-by key (:derived r))]
    (doseq [o (:over d)]
      (when-not (or (= :now o) (contains? dkeys o))
        (err kind :derived-cycles
             (str "derived field " fact ": cross-kind derived inputs read "
                  "through aggregate edges only; over= names " o " — neither "
                  ":now nor a data field of " kind))))
    (when-some [c (:count d)]
      (check-aggregate-edge reg kind r fact "count" c))
    (when-some [c (:sum d)]
      (check-aggregate-edge reg kind r fact "sum" c)))
  (check-fact-dag reg))

;; ── link :where — the narrowed owns embed ───────────────────────────

(defn- check-link-where
  "An owns link's :where narrows its compiled href — it must be a
  query the target collection answers: :state (declared states) or an
  :eq/:in-filterable field, the same contract as :pick. A :where on
  an edge or template link has no compiled home and refuses."
  [reg]
  (doseq [[kind r] (:kinds reg)
          ld (:links r)
          :when (contains? ld :where)]
    (when-not (:owns ld)
      (err kind :links (str "link " (:rel ld) ": :where narrows a compiled "
                            ":owns href — an :edge carries its own :on and "
                            "a template :href spells its own params")))
    (when-not (map? (:where ld))
      (err kind :links (str "link " (:rel ld) ": :where is a "
                            "{field value(s)} query map")))
    (when-some [target (get-in reg [:kinds (:owns ld)])]
      (doseq [[f v] (:where ld)]
        (if (= :state f)
          (let [states (into #{} (map name) (:states target))]
            (doseq [x (if (coll? v) v [v])
                    :let [x (if (keyword? x) (name x) (str x))]]
              (when-not (contains? states x)
                (err kind :links (str "link " (:rel ld) ": :where state " x
                                      " is not a state of "
                                      (name (:owns ld)))))))
          (when-not (some #{:eq :in} (get-in target [:filterable f]))
            (err kind :links (str "link " (:rel ld) ": :where key " f
                                  " is not an :eq/:in-filterable field of "
                                  (name (:owns ld)))))))))
  nil)

;; ── pick: the declared picker query ─────────────────────────────────

(defn- check-pick
  "A ref's :pick is the picker's collection query — presentation, but
  it must be a query the target actually answers: each key is :state
  or an :eq/:in-filterable field of the target kind, and :state
  values are declared states. A picker that fetches a 400 is worse
  than an unfiltered one."
  [reg]
  (doseq [[kind r] (:kinds reg)
          [where form] (surfaces r)
          :let [entries (schema/entry-map form)]
          f (schema/entry-keys form)
          :let [{props :properties s :schema} (get entries f)
                pick (:pick props)]
          :when (and pick (= :waymark/ref (head s)))]
    (let [target (get-in reg [:kinds (:kind props)])
          site (str where "." (name f))]
      (when-not (map? pick)
        (err kind :pick (str site ": :pick is a {field value(s)} query map")))
      (when target
        (doseq [[pf pv] pick]
          (if (= :state pf)
            (let [states (into #{} (map name) (:states target))]
              (doseq [v (if (coll? pv) pv [pv])
                      :let [v (if (keyword? v) (name v) (str v))]]
                (when-not (contains? states v)
                  (err kind :pick (str site ": :pick state " v " is not a "
                                       "state of " (name (:kind props)))))))
            (let [ops (get-in target [:filterable pf])]
              (when-not (some #{:eq :in} ops)
                (err kind :pick (str site ": :pick key " pf " is not an "
                                     ":eq/:in-filterable field of "
                                     (name (:kind props)))))))))))
  nil)

;; ── touches: the declared cross-write sets ──────────────────────────

(defn- check-touches
  "Every :touches entry names a real kind and a real action of it
  (errors — an advertisement of a write that cannot happen is a lie
  at the def site). An owns-cascade :on target the parent action does
  not advertise is a coverage WARNING: the cascade IS a touch, and
  the envelope should say so."
  [reg]
  (let [kinds (:kinds reg)]
    (doseq [[kind r] kinds
            [aname a] (:actions r)
            t (:touches a)]
      (let [target (get kinds (:kind t))]
        (when-not target
          (err kind :touches (str "action " (name aname) " touches kind "
                                  (:kind t) ", which is not registered")))
        (when-not (get-in target [:actions (:action t)])
          (err kind :touches (str "action " (name aname) " touches "
                                  (name (:kind t)) "." (name (:action t))
                                  ", not an action of that kind")))))
    (into []
          (for [[kind r] kinds
                edge (:owns r)
                [parent-action child-action] (:on edge)
                :let [declared (get-in r [:actions parent-action :touches])]
                :when (not-any? #(and (= (:kind %) (:kind edge))
                                      (= (:action %) child-action))
                                declared)]
            (str "[touches] " (name kind) ": action " (name parent-action)
                 " cascades " (name (:kind edge)) "." (name child-action)
                 " but does not advertise it — declare :touches "
                 "[{:kind " (:kind edge) " :action " child-action
                 " :may true}]")))))

;; ── the battery ─────────────────────────────────────────────────────

;; punt: check-compounds (compound inputs) — its declaration shape is
;; unported; the check arrives with the feature.

(defn run-all
  "The assembly battery in waymark9 order: refs, owns, related,
  derived-cycles, touches. Throws the first error, returns
  {:warnings [str …]}."
  [reg]
  {:warnings
   (into []
         (mapcat #(% reg))
         [check-refs check-owns check-related check-derived-cycles
          check-touches check-pick check-link-where])})
