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
            [waymark10.checks :as checks]
            [waymark10.expr :as expr]
            [waymark10.recurs :as recurs]
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

;; ── check-external-refs ─────────────────────────────────────────────

(def ^:private default-external-match
  "mirror/default-match, restated: requiring that namespace here would
  close a cycle (mirror → invoke → … → registry → this battery)."
  :external_id)

(defn- check-external-refs
  "An external-keyed ref resolves its sibling's external id against ONE
  field of the TARGET kind (mirror.clj's :match, :external_id unsaid).
  The def-site check judges what one declaration can see; the target is
  knowable only here: the matched field must exist and be
  :eq-filterable, because resolution is one indexed read per distinct
  id — on the promoted column or not at all. An unsaid :match against a
  native kind is the silent form of the same error: no external_id
  exists there, so every resolution answers nil forever."
  [reg]
  (doseq [[kind r] (sort-by key (:kinds reg))
          [f {props :properties}] (schema/entry-map (:schema r))
          :when (:external-key props)
          ;; check-refs already refused a ref naming an unregistered kind
          :let [target (:kind props)
                tdef (get-in reg [:kinds target])]
          :when tdef]
    (let [match (:match props default-external-match)
          where (str "data." (name f) ": ")]
      (if (= default-external-match match)
        (when-not (:mirror tdef)
          (err kind :refs
               (str where "the ref matches " target "'s external_id, but "
                    (name target) " is a native kind and carries none — "
                    "name the field it should match with :match")))
        (do
          (when-not (contains? (schema/entry-map (:schema tdef)) match)
            (err kind :refs
                 (str where ":match " match " is not a field of "
                      (name target) "'s data schema")))
          (when-not (contains? (set (get (:filterable tdef) match)) :eq)
            (err kind :refs
                 (str where ":match " match " must be :eq-filterable on "
                      (name target) " — every resolution is an indexed "
                      "read on the promoted column"))))))))

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
      (when-not (or (contains? #{:now :today} o) (contains? dkeys o))
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
        ;; a touch may name the target's CREATE door too (ctx :create,
        ;; chore's queue verb) — create names live beside :actions
        (when-not (or (get-in target [:actions (:action t)])
                      (contains? (or (:create-action-names target) #{:create})
                                 (:action t)))
          (err kind :touches (str "action " (name aname) " touches "
                                  (name (:kind t)) "." (name (:action t))
                                  ", not an action (or the create door) "
                                  "of that kind")))))
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

;; ── process: the steps name real doors ──────────────────────────────

(defn- ref-field?
  "Is `f` a :waymark/ref entry of `r`'s data schema pointing at `tk`?"
  [r f tk]
  (let [entry (get (schema/entry-map (:schema r)) f)]
    (and entry
         (= :waymark/ref (head (:schema entry)))
         (= tk (get-in entry [:properties :kind])))))

(defn- check-process-door
  "One step door (a :do or an :undo), judged where the target is
  known: the kind is registered, the field the step reads is a ref at
  that kind, the action exists and is one the cross-write door can
  open body-less where the step sends no input — never bulk, never
  fenced (the process holds no etag, exactly as the cascade runner
  does not)."
  [kinds kind r sname what {:keys [door id-field action input] :as d}]
  (let [tk (:kind d)
        where (str "process step " (name sname) " " what ": ")
        target (get kinds tk)]
    (when-not target
      (err kind :process (str where "kind " tk " is not registered on this engine")))
    (if (= :create door)
      (when-not (contains? (or (:create-action-names target) #{:create}) :create)
        (err kind :process (str where (name tk) " births through a create door "
                                "named " (vec (sort (:create-action-names target)))
                                ", not :create — the process can only reach a "
                                "kind whose create door is named :create")))
      (let [act (get-in target [:actions action])]
        (when-not (ref-field? r id-field tk)
          (err kind :process (str where "field " id-field " must be a :waymark/ref "
                                  "entry of " (name kind) " whose :kind is " tk)))
        (when-not act
          (err kind :process (str where (name tk) "." (name action)
                                  " is not an action of that kind")))
        (when (:bulk act)
          (err kind :process (str where (name tk) "." (name action)
                                  " is a bulk door — a collection affordance, "
                                  "not a row's")))
        (when (get-in act [:safety :fence])
          (err kind :process (str where (name tk) "." (name action)
                                  " is fenced — the process holds no etag")))
        (when (and (some? input) (nil? (:input act)))
          (err kind :process (str where (name tk) "." (name action)
                                  " takes no input, but the step sends one")))
        (when (and (nil? input) (:input act)
                   (seq (schema/entry-keys (:input act))))
          ;; a door with a required input the step does not spell
          ;; would refuse every run — say so here
          (let [required (into []
                               (keep (fn [[k e]] (when-not (:optional e) k)))
                               (sort-by key (schema/entry-map (:input act))))]
            (when (seq required)
              (err kind :process (str where (name tk) "." (name action)
                                      " requires input " required
                                      ", and the step sends none")))))))))

(defn- check-process
  "The :process sugar's cross-kind half (its single-kind half ran at
  the def site, waymark10.process/desugar). Every step's :do and
  :undo name a real door the cross-write doors can open; an undo
  departs from where its do landed, so the compensation is honest
  the same way verify-undo-pointers keeps a plain :undo honest."
  [reg]
  (let [kinds (:kinds reg)]
    (doseq [[kind r] (sort-by key kinds)
            :let [p (:process r)]
            :when p
            s (:steps p)]
      (let [sname (:name s)
            d (:do s)]
        (check-process-door kinds kind r sname ":do" d)
        (when-some [u (:undo s)]
          (check-process-door kinds kind r sname ":undo" u)
          (let [target (get kinds (:kind u))
                undo (get-in target [:actions (:action u)])
                landed (if (= :create (:door d))
                         (when (= (:kind d) (:kind u)) (:initial target))
                         (get-in kinds [(:kind d) :actions (:action d) :to]))]
            (when (and undo landed (not (contains? (:from undo) landed)))
              (err kind :process
                   (str "process step " (name sname) " :undo "
                        (name (:kind u)) "." (name (:action u))
                        " does not depart from " landed ", where the step's "
                        ":do lands — it is not this step's reverse")))))))))

;; ── follow: the field is on the kind it reaches ─────────────────────

(defn- garnish-keys
  "The data fields a kind's refs maintain beside its schema's own: each
  ref's :label and the targets of its :carry — written onto the row by
  the engine, so a follow reads them like any data field."
  [form]
  (into #{}
        (mapcat (fn [[_ {:keys [properties]}]]
                  (cond-> []
                    (keyword? (:label properties)) (conj (:label properties))
                    (map? (:carry properties)) (into (vals (:carry properties))))))
        (schema/entry-map form)))

(defn- check-follow-fields
  "[follow-unknown-field]: every follow reads a field its target kind
  has — a data field, a ref's maintained label or carry, :state or :id
  (docs/spec-related-row-law.md §6). Judged here, where the target's
  schema is known; a target that is not registered is check-refs' to
  name."
  [reg]
  (let [kinds (:kinds reg)]
    (doseq [[kind r] (sort-by key kinds)
            {:keys [door guard]} (checks/guard-sites r)
            :when (:when guard)
            [tk _ field] (mapcat expr/follows (cons (:when guard) (vals (:vars guard))))
            :let [target (get kinds tk)]
            :when (and target
                       (not (contains? #{:state :id} field))
                       (not (contains? (into (set (schema/entry-keys (:schema target)))
                                             (garnish-keys (:schema target)))
                                       field)))]
      (err kind :follow-unknown-field
           (str "guard " (name (:name guard)) " on " (name door) " follows "
                (name tk) "." (name field) ", a field " (name tk) " does not "
                "have — follow reads a data field, :state or :id")))
    []))

;; ── exists / none / count-rows: an index to ask, evidence on its kind ──

(defn- check-rows-queries
  "[exists-unfiltered]: every where field of an exists, none or
  count-rows is declared :filterable on the kind it asks — :id and
  :state are the row's own columns — an error, not a warning, because
  an unindexed guard query runs on every write. [remedy-on-evidence-kind]:
  a remedy whose :id is (evidence :b), b a binder, names a door of b's
  kind (docs/spec-related-row-law.md §3, §6). A `first` in :evidence is
  judged as a binder is: its where and its pred's wheres must ask
  indexed fields, and (evidence :name) must name a door of its kind."
  [reg]
  (let [kinds (:kinds reg)]
    (doseq [[kind r] (sort-by key kinds)
            {:keys [door guard]} (checks/guard-sites r)
            :let [firsts (when (map? (:evidence guard))
                           (into {} (filter (comp map? val)) (:evidence guard)))]
            :when (or (:when guard) (seq firsts))]
      (doseq [[tk where] (concat (mapcat expr/rows-queries (cons (:when guard) (vals (:vars guard))))
                                 (mapcat (fn [{:keys [kind where pred]}]
                                           (cons [kind where] (expr/rows-queries pred)))
                                         (vals firsts)))
              :let [target (get kinds tk)]
              :when target
              f (sort (keys where))
              :when (not (or (contains? #{:id :state} f)
                             (contains? (:filterable target) f)))]
        (err kind :exists-unfiltered
             (str "guard " (name (:name guard)) " on " (name door) " asks "
                  (name tk) " where " (name f) ", a field " (name tk)
                  " does not declare :filterable — an unindexed guard query "
                  "runs on every write")))
      (doseq [rmd (:remedies guard)
              :when (map? rmd)
              :let [[op b] (when (seq? (:id rmd)) (:id rmd))
                    tk (or (get-in guard [:binders b :kind])
                           (get-in firsts [b :kind]))]
              :when (and (= 'evidence op) tk
                         (not= (name tk) (namespace (:door rmd))))]
        (err kind :remedy-on-evidence-kind
             (str "guard " (name (:name guard)) " on " (name door) ": remedy "
                  (pr-str (:door rmd)) " acts on (evidence " b "), a row of "
                  (name tk) " — name a door of " (name tk))))))
  [])

;; ── the calendar's findings (docs/spec-calendar-time.md §7) ─────────

(defn- guard-forms
  "An expression guard's :when and its :vars' forms; nothing for a code
  guard."
  [guard]
  (when-some [w (:when guard)]
    (cons w (when (map? (:vars guard)) (remove nil? (vals (:vars guard)))))))

(defn- utc-today?
  "(date-of (now)) or (date-of (var :now)): Greenwich's day."
  [x]
  (and (seq? x) (= 'date-of (first x))
       (let [a (second x)] (or (= a '(now)) (= a '(var :now))))))

(defn- utc-today-hits
  "The date fields of `r` a comparison in `form` sets against
  (date-of (now)) or (date-of (var :now))."
  [r form]
  (let [nodes #(tree-seq seq? rest %)
        date-field (fn [x]
                     (when (and (seq? x) (contains? '#{data input var} (first x))
                                (keyword? (second x))
                                (= :waymark/date
                                   (head (schema/field-schema (:schema r) (second x)))))
                       (second x)))]
    (for [x (nodes form)
          :when (and (seq? x) (contains? '#{= not= < <= > >=} (first x)) (= 3 (count x)))
          :let [[_ a b] x]
          [today other] [[a b] [b a]]
          :when (some utc-today? (nodes today))
          f (keep date-field (nodes other))]
      f)))

(defn- check-utc-today
  "[utc-today], a warning: a guard or fact that compares (date-of (now))
  — or (date-of (var :now)) — with a date field asks Greenwich's day,
  not the house's."
  [reg]
  (into []
        (mapcat (fn [[kind r]]
                  (concat
                   (for [{:keys [door guard]} (checks/guard-sites r)
                         form (guard-forms guard)
                         f (distinct (utc-today-hits r form))]
                     (str (name kind) " [utc-today] guard " (some-> (:name guard) name)
                          " on " (name door) " compares (date-of (now)) with " (name f)
                          " — that is Greenwich's day; use (today), which is the house's"))
                   (for [[fact d] (sort-by key (:derived r))
                         :when (:expr d)
                         f (distinct (utc-today-hits r (:expr d)))]
                     (str (name kind) " [utc-today] derived field " (name fact)
                          " compares (date-of (var :now)) with " (name f)
                          " — that is Greenwich's day; name :today in :over and "
                          "read (var :today), which is the house's")))))
        (sort-by key (:kinds reg))))

(defn calendar-readers
  "The laws of `reg` that read the work calendar: `kind door guard` for
  each guard whose tree asks (today), business-days, working? or
  another calendar reader, `kind fact` for each derived fact that does
  or names :today in :over, and `kind state :expected-age` for each age
  in `{:business-days n}` or `{:until form}` — live-cracks counts those
  on the default calendar, and with none it ages nothing. What
  [business-days-no-calendar] names at boot
  (waymark10.server.engine/ensure-default-calendar!)."
  [reg]
  (into []
        (mapcat (fn [[kind r]]
                  (concat
                   (for [{:keys [door guard]} (checks/guard-sites r)
                         :when (some #(:uses-calendar (expr/info %)) (guard-forms guard))]
                     (str (name kind) " " (name door) " " (some-> (:name guard) name)))
                   (for [[fact d] (sort-by key (:derived r))
                         :when (or (some #{:today} (:over d))
                                   (some-> (:expr d) expr/info :uses-calendar))]
                     (str (name kind) " " (name fact)))
                   (for [[state age] (sort-by (comp str key) (:expected-age r))
                         :when (and (map? age)
                                    (or (contains? age :business-days)
                                        (contains? age :until)))]
                     (str (name kind) " " (name state) " :expected-age")))))
        (sort-by key (:kinds reg))))

;; ── :occupies (docs/spec-calendar-time.md §5, §7) ───────────────────

(defn- occupations
  "Every :occupies entry of the registry as [kind entry target], target
  the kind its :resource ref names."
  [reg]
  (for [[kind r] (sort-by key (:kinds reg))
        o (:occupies r)
        :let [target (some (fn [{:keys [field kind listed]}]
                             (when (and (= field (:resource o)) (not listed)) kind))
                           (schema/ref-fields (:schema r)))]]
    [kind o target]))

(defn- check-occupies-unfiltered
  "[occupies-unfiltered]: the resource, from and to fields of every
  :occupies entry are :filterable on the occupying kind — the derived
  -is-free guard asks them on every write ([exists-unfiltered]'s
  reason, named for the declaration that wrote the query)."
  [reg]
  (doseq [[kind o _] (occupations reg)
          f [(:resource o) (:from o) (:to o)]
          :when (not (contains? (get-in reg [:kinds kind :filterable]) f))]
    (err kind :occupies-unfiltered
         (str ":occupies holds " (name (:resource o)) " by " (name f) ", a field "
              (name kind) " does not declare :filterable — the derived guard asks it "
              "on every write")))
  [])

(defn- check-resource-uncalendared
  "[resource-uncalendared], info: an occupied kind that names no
  :calendar — its -is-working guards read the default work_calendar,
  the whole day when that names no hours."
  [reg]
  (vec (for [target (sort (distinct (keep last (occupations reg))))
             :when (nil? (get-in reg [:kinds target :calendar]))]
         (str (name target) " [resource-uncalendared] " (name target)
              " is occupied and names no :calendar — its -is-working guards read "
              "the default work_calendar. What hours does a "
              (str/replace (name target) "_" " ") " work?"))))

;; ── :recurs (docs/spec-calendar-time.md §4, §7) ─────────────────────

(defn- required-create-fields
  "The fields `target`'s create door cannot do without: its create
  schema's entries that are neither optional nor defaulted."
  [target]
  (sort (for [[f e] (schema/entry-map (or (:create-schema target) (:schema target)))
              :when (not (or (:optional e) (contains? (:properties e) :default)))]
          f)))

(defn- check-recurs
  "Every :recurs rule of the registry: its shape ([recurs], from
  waymark10.recurs/rule-problems), then the three findings — an owner
  ([recurs-no-owner]), a stop ([recurs-no-stop]), and a made kind whose
  create door the rule's :input opens ([recurs-unmakeable])."
  [reg]
  (doseq [[kind r] (sort-by key (:kinds reg))
          :let [rules (:recurs r)]
          :when (some? rules)
          rule (if (sequential? rules)
                 rules
                 (err kind :recurs ":recurs is a vector of rules [{:name … :makes … :every … :from …}]"))]
    (when-some [p (first (recurs/rule-problems r rule))]
      (err kind :recurs p))
    (let [rname (name (:name rule))
          makes (name (:makes rule))
          said (str/replace makes "_" " ")
          target (get-in reg [:kinds (:makes rule)])]
      (when (nil? (:owner rule))
        (err kind :recurs-no-owner
             (str "rule " rname " makes " makes " rows with no :owner — name the field or "
                  "seat whose act the made row is. Whose name goes on each " said
                  " this makes?")))
      (when (every? #(nil? (get rule %)) [:until :times :while])
        (err kind :recurs-no-stop
             (str "rule " rname " has none of :until, :times or :while — add the season's "
                  "end, a count, or the states that keep it going. When should the " said
                  "s stop coming?")))
      (when (or (nil? target) (empty? (or (:create-action-names target) #{:create})))
        (err kind :recurs-unmakeable
             (str "rule " rname " makes " makes ", "
                  (if target "a kind with no create door" "a kind this registry does not hold"))))
      (when-some [missing (seq (remove (set (keys (:input rule))) (required-create-fields target)))]
        (err kind :recurs-unmakeable
             (str "rule " rname "'s :input misses " (str/join ", " (map name missing))
                  ", which " makes "'s create door requires — fill the input from the source row")))))
  [])

;; ── the battery ─────────────────────────────────────────────────────

;; punt: check-compounds (compound inputs) — its declaration shape is
;; unported; the check arrives with the feature.

(defn- check-unref'd-ids
  "Every kind through waymark10.checks/unref'd-id-hits: a string field
  named after a registered kind must be a ref or say why not. Judged
  here because only the registry knows which names are kinds. One
  failure for the whole registry: a line per hit with its kind in
  front, then the remedy once, last — a summary that keeps only the
  head of the message still shows a whole hit."
  [reg]
  (let [kinds (set (keys (:kinds reg)))
        hits (into []
                   (mapcat (fn [[kind r]]
                             (map #(str (name kind) " [unref'd-ids] " %)
                                  (concat (checks/unref'd-id-hits r kinds)
                                          (checks/summary-id-hits r)))))
                   (sort-by key (:kinds reg)))]
    (when (seq hits)
      (throw (t/definition-error
              (str/join "\n" (conj hits (str "[unref'd-ids] "
                                             checks/unref'd-ids-remedy)))
              {:check :unref'd-ids})))))

(defn run-all
  "The assembly battery in waymark9 order: refs (and the external-keyed
  refs' targets, v10's own), owns, related, derived-cycles, touches —
  and process, v10's own, whose steps name doors of other kinds.
  Throws the first error, returns {:warnings [str …] :info [str …]}."
  [reg]
  {:warnings
   (into []
         (mapcat #(% reg))
         [check-refs check-external-refs check-owns check-related
          check-derived-cycles check-touches check-process check-pick
          check-link-where check-follow-fields check-occupies-unfiltered check-recurs
          check-rows-queries check-utc-today check-unref'd-ids])
   :info (check-resource-uncalendared reg)})
