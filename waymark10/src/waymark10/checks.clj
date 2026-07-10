(ns waymark10.checks
  "The fail-fast gate: the import-time validator battery over the
  normalized resource map, ported one-for-one by name from waymark9
  core/checks.py. A declaration that fails never serves a request.

  run-all returns {:warnings [str …]} or throws the named check's
  definition error (ex-data carries {:check <name>}). Warnings are
  \"[check-name] …\" strings the resource gate surfaces on *err*.

  Phase-2 punts: check-when (conditional demand) and check-authored
  wait for their features. The assembly-time battery — check-refs,
  check-owns, check-related, cross-kind derived inputs — lives in
  waymark10.checks-assembly and runs at registry construction;
  check-touches and check-compounds remain unported."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- err [rmap check msg]
  (throw (t/definition-error
          (str (name (:kind rmap)) " [" (name check) "] " msg)
          {:check check})))

(def ^:private snake #"[a-z][a-z0-9_]*")

(def ^:private waivable #{:altitude :large-effort})

(def ^:private long-text-budget 280)

;; ── shared introspection ────────────────────────────────────────────

(defn- leaf-guards [a] (mapcat g/iter-leaves (:guards a)))

(defn- input-keys [a] (when (:input a) (set (schema/entry-keys (:input a)))))

(defn- data-keys [r] (set (schema/entry-keys (:schema r))))

(defn- item-map-form
  "The [:map …] item form of a vector-of-map field, nil otherwise."
  [form k]
  (when-some [s (schema/field-schema form k)]
    (when (and (vector? s) (= :vector (first s)))
      (let [item (last s)]
        (when (and (vector? item) (= :map (first item)))
          item)))))

;; ── the machine ─────────────────────────────────────────────────────

(defn- check-tokens [r]
  (let [states (set (:states r))
        snake? #(boolean (re-matches snake (name %)))]
    (when-not (snake? (:kind r))
      (err r :tokens (str "kind must be a snake_case token, got " (:kind r))))
    (doseq [s (:states r)]
      (when-not (snake? s)
        (err r :tokens (str "state token " s " is not snake_case"))))
    (when-not (contains? states (:initial r))
      (err r :tokens (str "initial state " (:initial r) " is not a declared state")))
    (when-some [unknown (seq (sort (remove states (:terminal r))))]
      (err r :tokens (str "terminal states " (vec unknown) " are not declared states")))
    (doseq [a (machine/actions-seq r)]
      (when-some [bad (seq (sort (remove states (conj (:from a) (:to a)))))]
        (err r :tokens (str "action " (:name a) " references undeclared states "
                            (vec bad)))))))

(defn- check-reachability [r]
  (let [{:keys [unreachable no-exit]} (machine/dead-states r)
        unreachable (set unreachable)
        no-exit (set no-exit)]
    (doseq [s (:states r) :when (not (contains? (:allow-dead r) s))]
      (when (unreachable s)
        (err r :reachability
             (str "state " s " is unreachable from " (:initial r)
                  " — annotate :allow-dead if intentional")))
      (when (no-exit s)
        (err r :reachability
             (str "state " s " is a dead end: non-terminal with no outgoing "
                  "transitions — annotate :allow-dead if intentional"))))))

(defn- check-terminal-no-exit [r]
  (doseq [a (machine/actions-seq r)]
    (when-some [dead (seq (sort (set/intersection (:from a) (:terminal r))))]
      (err r :terminal-no-exit
           (str "action " (:name a) " exits terminal state(s) " (vec dead))))))

(defn- usable-reverse?
  "Unconditional-or-time-based, approximated: no leaf hidden, none
  role-token-gated."
  [action]
  (not-any? #(or (:hide %)
                 (some-> (:requires-token %) (str/starts-with? "role:")))
            (leaf-guards action)))

(defn- check-reversible [r]
  (doseq [a (machine/actions-seq r)
          :when (get-in a [:safety :reversible])
          [src reverses] (sort-by key (machine/reverse-edges r a))]
    (when-not (some usable-reverse? reverses)
      (err r :reversible
           (str "action " (:name a) " declares :reversible true but no "
                "unconditional transition " (:to a) " → " src " exists")))))

(defn- check-one-way [r]
  (doseq [a (machine/actions-seq r)]
    (let [s (:safety a)]
      (when-not (or (:reversible s) (:confirm s) (:one-way s))
        ;; self-loops are exempt: re-doing is its own undo
        (when (some #(not= % (:to a)) (:from a))
          (err r :one-way
               (str "action " (:name a) " is irreversible, unconfirmed, and "
                    "leaves the current state — a silent one-way door; declare "
                    ":one-way (acknowledged), :confirm, or an honest reverse")))))))

;; ── guards ──────────────────────────────────────────────────────────

(defn- check-guard-declarations [r]
  (doseq [a (machine/actions-seq r)
          lg (leaf-guards a)
          :when (seq (:judges lg))]
    (if-not (:input a)
      (err r :guard-declarations
           (str "guard " (:name lg) " on action " (:name a) " judges "
                (vec (:judges lg)) " but the action takes no input"))
      (when-some [missing (seq (remove (input-keys a) (:judges lg)))]
        (err r :guard-declarations
             (str "guard " (:name lg) " on action " (:name a) " judges "
                  (vec missing) ", not field(s) of its input"))))))

(def ^:private placeholder #"\{([A-Za-z0-9_.-]+)\}")

(defn- template-placeholders
  "Root keywords an explain template reads ({date} and {day.date} → :date)."
  [explain]
  (into #{}
        (map (fn [[_ p]] (keyword (first (str/split p #"\.")))))
        (re-seq placeholder (or explain ""))))

(defn- declared-vars
  "Expr guards declare {kw form} garnish; code guards a vector of the
  names their :vars-fn supplies."
  [lg]
  (let [vars (:vars lg)]
    (cond
      (map? vars) (set (keys vars))
      (sequential? vars) (into #{} (map keyword) vars)
      :else #{})))

(defn- orphan-placeholders [lg]
  (let [known (into (declared-vars lg) (:judges lg))]
    (seq (sort (remove known (template-placeholders (:explain lg)))))))

(defn- check-guard-templates [r]
  (doseq [a (machine/actions-seq r)
          lg (leaf-guards a)]
    (when-some [orphans (orphan-placeholders lg)]
      (err r :guard-templates
           (str "guard " (:name lg) " on action " (:name a) ": explain references "
                (vec orphans) ", which neither :vars nor its judged fields supply")))))

(defn- check-create-guards [r]
  (let [model-keys (set (schema/entry-keys (or (:create-schema r) (:schema r))))]
    (doseq [top (:create-guards r)
            lg (g/iter-leaves top)]
      (when-some [missing (seq (remove model-keys (:judges lg)))]
        (err r :create-guards
             (str "create guard " (:name lg) " judges " (vec missing)
                  ", not field(s) of the create schema")))
      (when-some [orphans (orphan-placeholders lg)]
        (err r :create-guards
             (str "create guard " (:name lg) ": explain references " (vec orphans)
                  ", which neither :vars nor its judged fields supply"))))))

;; ── the closure rule (design §1) ────────────────────────────────────

(def ^:private constraint-keys
  [:enum :const :format :minimum :maximum :exclusiveMinimum :exclusiveMaximum
   :minLength :maxLength :pattern])

(defn- constrained?
  "Does the published JSON-Schema property give a client anything to go
  on? Enum/const/format/bounds/pattern, a boolean, a declared widget —
  or any non-null anyOf/oneOf branch that does."
  [prop]
  (boolean
   (and (map? prop)
        (or (some #(contains? prop %) constraint-keys)
            (= "boolean" (:type prop))
            (get-in prop [:x-display :widget])
            (some #(and (map? %) (not= "null" (:type %)) (constrained? %))
                  (concat (:anyOf prop) (:oneOf prop)))))))

(defn- check-closure [r]
  (doseq [a (machine/actions-seq r)
          :when (:input a)
          :let [js (delay (schema/json-schema (:input a)))]
          lg (leaf-guards a)
          :when (and (seq (:judges lg)) (nil? (:open lg)))]
    (let [covered (cond-> #{}
                    (:accepts lg) (into (if (:relation lg)
                                          (:judges lg)
                                          [(first (:judges lg))]))
                    (and (:relation lg) (:op lg)) (into (:judges lg)))]
      (doseq [f (:judges lg)
              :when (not (covered f))]
        (when-not (constrained? (get-in @js [:properties f]))
          (err r :closure
               (str "guard " (:name lg) " on action " (:name a) " judges " f
                    ", but nothing tells the client what " f " wants: no accepts, "
                    "no relation, no schema constraint — or acknowledge with :open")))))))

;; ── the surfaces ────────────────────────────────────────────────────

(defn- check-handler-signatures [r]
  (into []
        (keep (fn [a]
                (when-some [h (:handler a)]
                  (when-not (fn? h)
                    (err r :handler-signatures
                         (str "action " (:name a) " :handler is not a function")))
                  (when-not (:waymark10/form (meta h))
                    (str "[handler-signatures] action " (name (:name a))
                         " handler has no stateable identity — declare it with "
                         "defhandler")))))
        (machine/actions-seq r)))

(def ^:private template-root #"\{([A-Za-z0-9_]+)")

(defn- check-summary-template [r]
  (let [roots (into #{}
                    (map (comp keyword second))
                    (re-seq template-root (str (:summary r))))
        allowed #{:id :state :data :kind :version}]
    (when (empty? roots)
      (err r :summary-template "summary template reads no field"))
    (when-some [unknown (seq (sort (remove allowed roots)))]
      (err r :summary-template
           (str "summary template references unknown roots " (vec unknown)
                "; allowed: " (vec (sort allowed)))))))

(defn- check-waive-tokens [r]
  (doseq [a (machine/actions-seq r)]
    (when-some [unknown (seq (sort (remove waivable (:waives a))))]
      (err r :waive-tokens
           (str "action " (:name a) " waives unknown usability checks "
                (vec unknown) "; waivable: " (vec (sort waivable)))))))

(defn- check-place [r]
  (doseq [a (machine/actions-seq r)
          :when (:place a)]
    (when-not (:input a)
      (err r :place (str "action " (:name a) " declares :place " (:place a)
                         " but takes no input to bind the key into")))
    (let [scope (get-in r [:part-scopes (:place a)])]
      (when-not scope
        (err r :place (str "action " (:name a) " :place " (:place a)
                           " names no :part-scopes entry")))
      (let [{:keys [path key]} scope
            item (item-map-form (:schema r) path)]
        (when-not item
          (err r :place (str "action " (:name a) " place names data." (name path)
                             ", which is not a vector-of-map data field")))
        (when-not (contains? (set (schema/entry-keys item)) key)
          (err r :place (str "action " (:name a) " place key " key
                             " is not a field of data." (name path) " items")))
        (when-not (contains? (input-keys a) key)
          (err r :place (str "action " (:name a) " place key " key
                             " is not a field of its input")))))))

(defn- prose-widget? [props]
  (= "prose" (get-in props [:x-display :widget])))

(defn- check-edit
  "Edit declarations validate hard; edit-shaped actions that never
  declared one get the heuristic warning, and required prose without a
  draft gets the knowledge-floor warning (design §10)."
  [r]
  (let [dform (:schema r)
        dkeys (data-keys r)]
    (into []
          (mapcat
           (fn [a]
             (when-some [edit (:edit a)]
               (when (and (:draft edit) (nil? (:input a)))
                 (err r :edit (str "action " (:name a) " declares a draft but "
                                   "takes no input — there is nothing to draft")))
               (doseq [f (:prefill edit)]
                 (when-not (and (:input a) (contains? (input-keys a) f))
                   (err r :edit (str "action " (:name a) " prefills " f
                                     ", not a field of its input")))
                 (when-not (contains? dkeys f)
                   (err r :edit (str "action " (:name a) " prefills " f
                                     ", but the data schema has no such field "
                                     "to prefill from")))))
             (when (and (:input a) (not (:bulk a)))
               (let [ikeys (schema/entry-keys (:input a))
                     entries (schema/entry-map (:input a))
                     mirrored (when-not (:edit a)
                                (seq (filter
                                      (fn [f]
                                        (when-some [df (schema/field-schema dform f)]
                                          (= df (schema/field-schema (:input a) f))))
                                      ikeys)))
                     prose-req (seq (filter
                                     (fn [f]
                                       (let [{:keys [optional properties]} (entries f)]
                                         (and (not optional)
                                              (prose-widget? properties))))
                                     ikeys))]
                 (cond-> []
                   mirrored
                   (conj (str "[edit] action " (name (:name a)) " is edit-shaped — "
                              "input field(s) " (vec mirrored) " mirror data fields "
                              "the document already holds; declare :edit {:prefill "
                              (vec mirrored) "}"))
                   (and prose-req
                        (not (get-in a [:edit :draft]))
                        (not (contains? (:waives a) :large-effort)))
                   (conj (str "[edit] action " (name (:name a)) " demands composition "
                              "with no draft (" (vec prose-req) ") — a mis-click "
                              "discards everything typed; declare :edit {:draft …} "
                              "or waive :large-effort")))))))
          (machine/actions-seq r))))

(defn- check-altitude
  "Wrong-altitude heuristic: an input field that keys the items of a
  data array re-asks the user to identify the item they are already
  looking at — the fix is a part scope."
  [r]
  (let [arrays (into []
                     (keep (fn [k]
                             (when-some [item (item-map-form (:schema r) k)]
                               [k item])))
                     (schema/entry-keys (:schema r)))]
    (when (seq arrays)
      (into []
            (mapcat
             (fn [a]
               (when (and (:input a) (not (:bulk a))
                          (not (contains? (:waives a) :altitude)))
                 (let [judged (into #{}
                                    (comp (filter #(empty? (:reads %)))
                                          (mapcat :judges))
                                    (leaf-guards a))
                       placed (when (:place a)
                                (get-in r [:part-scopes (:place a) :path]))]
                   (for [[dname item] arrays
                         :when (not= dname placed)
                         :let [matched (seq (filter
                                             (fn [f]
                                               (and (judged f)
                                                    (when-some [tf (schema/field-schema item f)]
                                                      (= tf (schema/field-schema (:input a) f)))))
                                             (schema/entry-keys (:input a))))]
                         :when matched]
                     (str "[altitude] action " (name (:name a)) " input field(s) "
                          (vec matched) " re-asks the user to identify an item of "
                          "data." (name dname) " — declare a part scope, or waive "
                          ":altitude"))))))
            (machine/actions-seq r)))))

(defn- long-text-warnings
  "String-typed entries (:maybe-unwrapped — optionality is not shape)
  lacking a max under the budget, a declared widget/hidden/raw, or
  ref-hood."
  [where form]
  (let [entries (schema/entry-map form)]
    (into []
          (keep (fn [k]
                  (let [{props :properties raw :schema} (entries k)
                        s (if (and (vector? raw) (= :maybe (first raw)))
                            (second raw)
                            raw)
                        text? (or (= s :string)
                                  (and (vector? s) (= :string (first s))))
                        s-max (when (and (vector? s) (map? (second s)))
                                (:max (second s)))
                        xd (:x-display props)]
                    (when (and text?
                               (not (and s-max (< s-max long-text-budget)))
                               (not (or (:widget xd) (:hidden xd) (:raw xd))))
                      (str "[long-text] " where "." (name k)
                           " is a text field with no shape: declare prose widget, "
                           "a max under " long-text-budget ", hidden, or raw")))))
          (schema/entry-keys form))))

(defn- check-long-text [r]
  (let [dform (:schema r)
        inputs (->> (machine/actions-seq r)
                    (filter :input)
                    (reduce (fn [[seen acc] a]
                              (if (contains? seen (:input a))
                                [seen acc]
                                [(conj seen (:input a))
                                 (conj acc [(str "input " (name (:name a)))
                                            (:input a)])]))
                            [#{} []])
                    second)
        surfaces (concat [["data" dform]]
                         (keep (fn [k]
                                 (when-some [item (item-map-form dform k)]
                                   [(str "data." (name k) "[]") item]))
                               (schema/entry-keys dform))
                         inputs)]
    (into [] (mapcat (fn [[where form]] (long-text-warnings where form))) surfaces)))

;; ── the query surface ───────────────────────────────────────────────

(defn- check-faceted [r]
  (doseq [f (:faceted r)
          :when (not= f :state)]
    (let [ops (set (get (:filterable r) f))]
      (when-not (seq (set/intersection ops #{:eq :in}))
        (err r :faceted (str "faceted field " f " is not :eq/:in-filterable; "
                             "declare it in :filterable first"))))))

(defn- check-oneof [r]
  (doseq [[gname spec] (sort-by key (:one-of r))]
    (let [in (first (:in spec))
          [where target-keys]
          (if in
            (if-some [item (item-map-form (:schema r) in)]
              [(str "data." (name in) "[]") (set (schema/entry-keys item))]
              (err r :oneof (str "one-of group " gname " :in names data." (name in)
                                 ", which is not a vector-of-map data field")))
            ["data" (data-keys r)])]
      (doseq [[arm fields] (sort-by key (:arms spec))
              f fields]
        (when-not (contains? target-keys f)
          (err r :oneof (str "one-of group " gname " arm " arm " names " f
                             ", not a field of " where))))
      (when-some [dup (->> (mapcat val (:arms spec))
                           frequencies
                           (keep (fn [[f n]] (when (< 1 n) f)))
                           sort
                           seq)]
        (err r :oneof (str "one-of group " gname " arms overlap on " (vec dup)
                           " — arms are exclusive"))))))

(defn- check-unique [r]
  (let [dkeys (data-keys r)
        promoted (into (set (keys (:filterable r)))
                       (get-in r [:sortable :fields]))
        u (:unique r)]
    (doseq [group (map #(if (keyword? %) [%] (vec %))
                       (if (keyword? u) [u] u))]
      (when (empty? group)
        (err r :unique "unique declares an empty field group"))
      (doseq [f group]
        (when-not (contains? dkeys f)
          (err r :unique (str "unique field " f " is not a data field")))
        (let [fs (schema/field-schema (:schema r) f)]
          (when (and (vector? fs) (= :vector (first fs)))
            (err r :unique (str "unique field " f " is an array — membership "
                                "has no single-value uniqueness"))))
        (when-not (contains? promoted f)
          (err r :unique (str "unique field " f " must be filterable or sortable "
                              "— uniqueness is enforced on the promoted column")))))))

(defn- check-links [r]
  (let [dkeys (data-keys r)]
    (doseq [ld (:links r)
            :when (:badge ld)]
      (when-not (contains? dkeys (:badge ld))
        (err r :links (str "link " (:rel ld) ": badge " (:badge ld)
                           " is not a data field — the badge renders the "
                           "instance's current value of that field"))))))

;; ── derivations and history ─────────────────────────────────────────

(defn- derived-cycle
  "A dependency cycle among derived facts ([:a :b :a]), nil when the
  graph is a DAG."
  [derived]
  (let [facts (set (keys derived))]
    (letfn [(visit [f stack on-stack]
              (if (contains? on-stack f)
                (conj (vec (drop-while #(not= % f) stack)) f)
                (some #(visit % (conj stack f) (conj on-stack f))
                      (filter facts (:over (get derived f))))))]
      (some #(visit % [] #{}) (sort (keys derived))))))

(defn- schema-head
  "The head type of a field's schema form, :maybe already unwrapped by
  field-schema: [:int {:min 0}] → :int."
  [s]
  (if (vector? s) (first s) s))

(defn- check-count-spec
  "One aggregate spec: {:count {:related <edge> | :owns <child-kind>,
  :where {field #{values}}}} — exactly one edge, a shaped where, an
  :int fact, no :over (a count's inputs ARE its edge). The edge's
  existence is an assembly question (checks-assembly)."
  [r fact d]
  (let [c (:count d)]
    (when (seq (:over d))
      (err r :derived (str "derived field " fact ": a count's inputs are its "
                           "declared edge; :over does not apply")))
    (when-not (and (map? c)
                   (= 1 (count (select-keys c [:related :owns])))
                   (every? #{:related :owns :where} (keys c))
                   (every? keyword? (vals (select-keys c [:related :owns]))))
      (err r :derived (str "derived field " fact ": :count is {:related <edge> "
                           "| :owns <child-kind>, :where {field #{values}}} — "
                           "exactly one edge")))
    (when (contains? c :where)
      (let [w (:where c)]
        (when-not (and (map? w) (seq w)
                       (every? keyword? (keys w))
                       (every? #(and (coll? %) (not (map? %)) (seq %)) (vals w)))
          (err r :derived (str "derived field " fact ": :count :where is a "
                               "{field #{values}} map of non-empty value sets")))))
    (when-not (= :int (schema-head (schema/field-schema (:schema r) fact)))
      (err r :derived (str "derived field " fact ": a count fact is an int — "
                           "declare " fact " as :int in the schema")))))

(defn- check-derived [r]
  (let [derived (:derived r)
        dkeys (data-keys r)]
    (doseq [[fact d] (sort-by key derived)]
      (when (= (contains? d :expr) (contains? d :count))
        (err r :derived (str "derived field " fact ": declare exactly one of "
                             ":expr (an expression fact) or :count (an "
                             "aggregate over a declared edge)")))
      (when (contains? d :count)
        (check-count-spec r fact d))
      (doseq [o (:over d)]
        (when-not (or (= :now o) (contains? dkeys o))
          (err r :derived (str "derived field " fact ": :over names " o
                               ", not a data field (or :now, the clock)"))))
      (when-some [e (:expr d)]
        (when-some [p (first (concat (expr/problems e)
                                     (expr/derived-scope-problems e (set (:over d)))))]
          (err r :derived (str "derived field " fact ": " p))))
      (when-not (contains? dkeys fact)
        (err r :derived (str "a derivation is a stored fact — declare " fact
                             " in the schema"))))
    (when-some [cycle (derived-cycle derived)]
      (err r :derived (str "derived facts form a cycle: "
                           (str/join " → " (map name cycle))
                           " — a fact defined in terms of itself defines nothing")))
    (when (seq derived)
      (doseq [a (machine/actions-seq r)
              :when (:input a)]
        (when-some [clash (seq (sort (filter (set (keys derived))
                                             (schema/entry-keys (:input a)))))]
          (err r :derived (str "action " (:name a) " input names derived field(s) "
                               (vec clash) " — derived values are engine-computed; "
                               "an action cannot write them")))))))

(defn- check-renames
  "The continuity map validates hard: :renames {:states {old new}
  :actions {old new}} — every key a retired snake token that is NOT
  still declared, every target reaching a declared state/action
  (directly or through the chain), no cycles. A rename that resolves
  to nothing would strand the boot check and the replay obligation."
  [r]
  (let [renames (:renames r {})]
    (when-some [unknown (seq (sort (remove #{:states :actions} (keys renames))))]
      (err r :renames (str ":renames declares unknown surface(s) " (vec unknown)
                           "; renames map :states and :actions")))
    (doseq [[what m declared]
            [["state" (:states renames) (set (:states r))]
             ["action" (:actions renames) (set (keys (:actions r)))]]]
      (doseq [[old target] (sort-by key m)]
        (doseq [tok [old target]]
          (when-not (and (keyword? tok) (re-matches snake (name tok)))
            (err r :renames (str what " rename " old " → " target ": "
                                 (pr-str tok) " is not a snake_case token"))))
        (when (contains? declared old)
          (err r :renames (str what " rename " old " → " target ": " old
                               " is still declared — a token cannot be both "
                               "live and renamed")))
        (loop [t target seen #{old}]
          (cond
            (contains? declared t) nil
            (contains? seen t)
            (err r :renames (str what " rename chain from " old
                                 " cycles at " t " — it reaches no declared "
                                 what))
            (contains? m t) (recur (get m t) (conj seen t))
            :else
            (err r :renames (str what " rename " old " → " target
                                 " reaches no declared " what
                                 " (directly or through the chain)"))))))))

(defn- check-unless [r]
  (doseq [a (machine/actions-seq r)]
    (when-some [tr (:unless a)]
      (when-not (contains? (:actions r) tr)
        (err r :unless (str "action " (:name a) " :unless " tr
                            " names no transition of this resource — the log "
                            "fact could never have an actor"))))))

(defn- check-require [r]
  (let [validate
        (fn [where lg]
          (when-some [fact (:require lg)]
            (when-not (contains? (:derived r) fact)
              (err r :require (str where ": require " fact " names no derived "
                                   "field — only a maintained fact can gate a "
                                   "transition")))
            (when-not (= :boolean (schema/field-schema (:schema r) fact))
              (err r :require (str where ": require " fact " gates on a non-bool "
                                   "derivation — a gate judges a truth, not a "
                                   "value; derive the predicate as its own bool "
                                   "field")))))]
    (doseq [a (machine/actions-seq r)
            lg (leaf-guards a)]
      (validate (str "action " (name (:name a))) lg))
    (doseq [top (:create-guards r)
            lg (g/iter-leaves top)]
      (validate "create guard" lg))))

;; check-when (conditional demand over input models) and check-authored
;; (authority-synced fields) are phase-2: their declarations are unported.

;; ── the battery ─────────────────────────────────────────────────────

(defn run-all
  "The full battery in waymark9 order; throws the first error, returns
  {:warnings [str …]}."
  [rmap]
  {:warnings
   (into []
         (mapcat #(% rmap))
         [check-tokens check-reachability check-terminal-no-exit
          check-reversible check-one-way check-guard-declarations
          check-guard-templates check-create-guards check-closure
          check-handler-signatures check-summary-template check-waive-tokens
          check-place check-edit check-altitude check-long-text
          check-faceted check-oneof check-unique check-links
          check-derived check-renames check-unless check-require])})
