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
            [waymark10.declaration :as declaration]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.machine :as machine]
            [waymark10.schema :as schema]
            [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- err [rmap check msg]
  (throw (t/definition-error
          (str (name (:kind rmap)) " [" (name check) "] " msg)
          {:check check})))

(def ^:private snake #"[a-z][a-z0-9_]*")

(def ^:private waivable #{:altitude :edit-shape :large-effort})

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

(defn- check-opaque-residue
  "The other half of the stateable-identity sentence (waymark-j82). A
  guard's :check/:accepts is fingerprinted exactly like a handler, and
  a BARE fn has no form to hash: since j82 it hashes by its ADDRESS,
  which is stable but says nothing about the body. So the law records
  where the opacity sits and cannot see it change. Warn where it sits
  — a warning now, the refusal callable-hash promises later. The cure
  is defguard, or a hand-minted :waymark10/form the way
  guards/not-the-field does it."
  [r]
  (into []
        (mapcat (fn [a]
                  (keep (fn [g]
                          (let [h (or (:check g) (:accepts g))]
                            (when (and (fn? h)
                                       (nil? (:when g))
                                       (nil? (:waymark10/form (meta h))))
                              (str "[opaque-residue] guard " (name (:name g))
                                   " on action " (name (:name a))
                                   " has no stateable identity — its "
                                   "fingerprint is its address, so a changed "
                                   "body is invisible to the law; declare it "
                                   "with defguard"))))
                        (:guards a []))))
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

(defn- vector-depth
  "How many :vector layers a schema form wears, :maybe layers seen
  through: [:maybe [:vector :string]] → 1, :string → 0."
  [s]
  (if (and (vector? s) (#{:maybe :vector} (first s)))
    (cond-> (vector-depth (last s)) (= :vector (first s)) inc)
    0))

(defn- base-shape
  "The comparable shape of one field's schema: [vector-depth leaf-type],
  nil when there is no leaf to name. This is the deliberately blunt
  reading the edit-shaped heuristic wants — schema/leaf-head already
  drops :maybe layers and property maps, so a tightened :max, a widened
  :max, or any other constraint tuning leaves the shape untouched, and
  schema/field-schema hands us the child form with the ENTRY properties
  (:optional, :x-display and friends) already left behind. What survives
  is 'an int is an int, a list of strings is a list of strings' — which
  is all a similarity question ever needed to ask."
  [s]
  (when-some [head (schema/leaf-head s)]
    [(vector-depth s) head]))

(defn- mirrors-data?
  "Does this input field mirror a data field of the same name? Same key
  in both, compatible base shape. NOT =: strict equality was equality
  doing a similarity job, and every ordinary drift — an added
  :x-display label, a tightened :max, a :maybe or :optional wrapper —
  made the schemas unequal, so the genuine near-mirror stopped warning
  and nobody was nudged toward :edit {:prefill …} (waymark-01f). A
  different base type (data holds a string, the input takes an int) or
  a name the data schema never declares still says no, so the heuristic
  stays a nudge and not noise."
  [dform iform f]
  (when-some [dshape (base-shape (schema/field-schema dform f))]
    (= dshape (base-shape (schema/field-schema iform f)))))

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
                     ;; :edit-shape is the escape hatch the looser
                     ;; heuristic earns (waymark-01f): name-and-shape
                     ;; cannot see that a field is EMPTY in the only
                     ;; state its action runs from — a door that welds
                     ;; a first value onto a blank looks exactly like
                     ;; one that rewrites an existing one
                     mirrored (when-not (or (:edit a)
                                            (contains? (:waives a) :edit-shape))
                                (seq (filter
                                      (fn [f] (mirrors-data? dform (:input a) f))
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
                    ;; a :secret entry never renders — no projection
                    ;; carries the value, so no display shape is owed
                    (when (and text?
                               (not (and s-max (< s-max long-text-budget)))
                               (not (or (:widget xd) (:hidden xd) (:raw xd)
                                        (:secret props))))
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

;; ── runtime vocabularies (waymark-8sg) ──────────────────────────────

(defn- check-options
  "The `:x-options` spelling, refused where it would publish a picker
  that fetches nothing. A typo in `:from` is silent otherwise — the
  projection simply omits the annotation and the field goes back to
  being a blank rectangle, which is exactly the failure this whole
  spelling exists to end.

  Three refusals: an unknown source, a source that is relative to a
  target kind with no `:of` naming the sibling field that carries it,
  and an `:of` naming a field the SAME form does not declare (the
  client interpolates the recipe from sibling values; a field on
  another surface is not in the form).

  Item maps are surfaces too (waymark-7rw). A grant's `:scope` is a
  vector of entries and the vocabulary belongs to the entry's parts —
  the kind it names, that kind's actions and fields — so the
  annotation lands INSIDE the item, where the fields it interpolates
  from are each other's siblings. The `:of` refusal is what makes that
  honest rather than convenient: an item field naming a sibling one
  level UP is refused here, because no client could fill a hole from
  outside the entry in front of the person. `check-long-text`'s
  `data.{field}[]` naming is reused so a refusal points at the right
  box."
  [r]
  (doseq [[where form] (mapcat
                        (fn [[where form]]
                          (cons [where form]
                                (keep (fn [k]
                                        (when-some [item (item-map-form form k)]
                                          [(str where ", " (name k) "[]") item]))
                                      (schema/entry-keys form))))
                        (cons ["the create door" (or (:create-schema r) (:schema r))]
                              (concat [["data" (:schema r)]]
                                      (for [a (machine/actions-seq r)
                                            :when (:input a)]
                                        [(str "action " (name (:name a)))
                                         (:input a)]))))
          :let [entries (schema/entry-map form)
                declared (set (keys entries))]
          [k {:keys [properties]}] entries
          :let [{:keys [from of composes]} (:x-options properties)]
          :when (some? (:x-options properties))]
    (let [src (get schema/option-sources from)]
      (when (nil? src)
        (err r :options
             (str where " field " k " declares :x-options {:from " (pr-str from)
                  "}, which names no runtime vocabulary; the sources are "
                  (vec (sort (map name (keys schema/option-sources)))))))
      (when (and (:needs-of src) (nil? of))
        (err r :options
             (str where " field " k " declares :x-options {:from " from
                  "}, whose options are relative to a target kind — name the "
                  "sibling field that carries it with :of")))
      (when (and of (not (contains? declared of)))
        (err r :options
             (str where " field " k " declares :x-options {:of " of
                  "}, which " where " does not declare — the recipe is "
                  "interpolated from SIBLING values, so :of must name a "
                  "field of the same form")))
      (when (and composes (not= :query composes))
        (err r :options
             (str where " field " k " declares :x-options {:composes "
                  (pr-str composes) "}; the only composition grammar is "
                  ":query (a field=value&… filter string)"))))))

;; ── the query surface ───────────────────────────────────────────────

(defn- check-filterable [r]
  (doseq [[f ops] (sort-by key (:filterable r))
          op (sort ops)]
    (when-not (contains? declaration/filter-ops op)
      (err r :filterable (str "filterable field " f " declares op " op
                              ", which is not a filter op; ops are "
                              (vec (sort declaration/filter-ops)))))))

(defn- check-sortable
  "The sortable surface's two refusals. A schema field NAMED
  created_at or updated_at shadows the engine column of that name:
  the declaration would promote f_created_at while every sort by
  created_at ordered by the engine's own column, so the same word
  would mean two things — a definition error, never a silent
  shadowing. And a :sortable field that is neither a data field nor
  one of those two timestamps promotes no column at all: today that
  survives the boot and fails at the first sorted page, which is the
  wrong place to learn it."
  [r]
  (let [dkeys (data-keys r)]
    (doseq [f (sort store/sortable-timestamps)]
      (when (contains? dkeys f)
        (err r :sortable
             (str "schema field " f " shadows the engine column of the same "
                  "name — every kind table carries created_at/updated_at, and "
                  "a sort by " (name f) " orders by that column; rename the "
                  "field"))))
    (doseq [f (get-in r [:sortable :fields])
            :when (not (or (contains? dkeys f)
                           (contains? store/sortable-timestamps f)))]
      (err r :sortable
           (str "sortable field " f " is neither a data field nor an engine "
                "timestamp — ordering runs over a promoted column, and "
                "nothing promotes " (name f)
                " (sortable timestamps are "
                (vec (sort (map name store/sortable-timestamps))) ")")))))

(defn- check-default-filters
  "A default filter must be a filter the door already accepts: it
  names a field the declaration filters by equality (or :state, which
  every kind filters by), and it carries a value that field's own
  schema admits. Caught here rather than at the first request, because
  a default nobody can express is a page that serves 422 to a caller
  who asked for nothing."
  [r]
  (let [states (into #{} (map name) (:states r))]
    (doseq [[f v] (sort-by key (:default-filters r))]
      (let [ops (set (get (:filterable r) f))
            array? (let [s (schema/field-schema (:schema r) f)]
                     (boolean (and (vector? s) (= :vector (first s)))))]
        (when-not (or (= :state f) (:eq ops) (:in ops) array?)
          (err r :default-filters
               (str "default filter " f " is not an :eq/:in-filterable field — "
                    "a default is an ordinary filter the caller could have "
                    "typed, so it can only name a param the grammar serves")))
        (doseq [value (if (str/includes? v ",") (str/split v #",") [v])
                :let [value (str/trim value)]]
          (if (= :state f)
            (when-not (contains? states value)
              (err r :default-filters
                   (str "default filter state=" (pr-str value)
                        " is not a state; one of " (vec (sort states)))))
            (when-some [problem (schema/filter-value-problem
                                 (schema/leaf-head (schema/field-schema
                                                    (:schema r) f))
                                 value)]
              (err r :default-filters
                   (str "default filter " (name f) "=" (pr-str value) " "
                        problem)))))))))

(defn- check-faceted [r]
  (doseq [f (:faceted r)
          :when (not= f :state)]
    (let [ops (set (get (:filterable r) f))]
      (when-not (seq (set/intersection ops #{:eq :in}))
        (err r :faceted (str "faceted field " f " is not :eq/:in-filterable; "
                             "declare it in :filterable first"))))))

(def ^:private view-kinds #{:deck :feed})

(defn where-field?
  "May a view's (or a saved_view's) `:where` name this field? `:state`
  always; an `:eq`/`:in`-filterable field, because a view's where is an
  ordinary filter the caller could have typed; an array field, whose
  containment filter is implicit in its shape."
  [r f]
  (boolean
   (or (= :state f)
       (let [ops (set (get (:filterable r) f))]
         (or (:eq ops) (:in ops)))
       (let [s (schema/field-schema (:schema r) f)]
         (and (vector? s) (= :vector (first s)))))))

(defn where-fields
  "Every field name a `:where` may put on the left of an `=`, sorted —
  the same question `where-field?` answers one field at a time, asked
  of the whole kind. Published on well-known so the runtime-vocabulary
  spelling (waymark-8sg) can offer the filter grammar as options
  instead of leaving a hand-authored query string to memory: the
  refusal and the picker read one list."
  [r]
  (into []
        (comp (distinct)
              (filter #(where-field? r %))
              (map name))
        (sort (concat [:state]
                      (keys (:filterable r))
                      (schema/entry-keys (:schema r))))))

(defn view-where-problems
  "A view's :where is a filter the door already accepts — the
  :default-filters law read twice: each field is :state or an
  :eq/:in-filterable field (or an array), each comma value one the
  field's own schema admits. Runs on the normalized (wire-string)
  form: {field-keyword wire-string}. Returns problem strings, empty
  when clean — shared verbatim by the declaration-time battery and
  the saved_view write gate (views as resources, waymark-rla)."
  [r where]
  (let [states (into #{} (map name) (:states r))]
    (into []
          (mapcat
           (fn [[f v]]
             (cond
               (not (keyword? f))
               [(str ":where key " (pr-str f) " is not a field keyword")]

               (not (where-field? r f))
               [(str ":where names " f ", which is not an "
                     ":eq/:in-filterable field — a view's where is an ordinary "
                     "filter the caller could have typed")]

               :else
               (into []
                     (keep (fn [value]
                             (let [value (str/trim value)]
                               (if (= :state f)
                                 (when-not (contains? states value)
                                   (str ":where state=" (pr-str value)
                                        " is not a state; one of "
                                        (vec (sort states))))
                                 (when-some [problem (schema/filter-value-problem
                                                      (schema/leaf-head
                                                       (schema/field-schema
                                                        (:schema r) f))
                                                      value)]
                                   (str ":where " (name f) "=" (pr-str value)
                                        " " problem))))))
                     (str/split (str v) #",")))))
          (sort-by key where))))

(defn deck-gesture-problems
  "One bound gesture of a :deck view. The action exists, it is
  reversible (a swipe is a snap judgment — every gesture must have an
  honest undo), it departs from EVERY state the view's :where shows
  (no card the gesture refuses), and it lands OUTSIDE them (a triaged
  card leaves the deck — the queue drains itself). Returns problem
  strings, empty when clean."
  [r side aname where-states]
  (if-not (keyword? aname)
    [(str side " must name an action, got " (pr-str aname))]
    (if-some [a (get (:actions r) aname)]
      (-> []
          (into (when-not (get-in a [:safety :reversible])
                  [(str side " binds " aname ", which is not "
                        "reversible — a swipe is a snap judgment; declare the "
                        "action's honest reverse (:undo)")]))
          (into (let [froms (into #{} (map name) (:from a))]
                  (when-some [uncovered (seq (sort (remove froms where-states)))]
                    [(str side " binds " aname ", which does not "
                          "depart from " (vec uncovered) " — every card the deck "
                          "shows must take the gesture")])))
          (into (when (contains? where-states (name (:to a)))
                  [(str side " binds " aname ", which lands in "
                        (:to a) " — still inside the deck's :where; a triaged card "
                        "must leave the queue")])))
      [(str side " names " aname ", which is not a declared action")])))

(defn view-problems
  "The core rules of ONE view map — {:kind (:deck|:feed) :where
  {field wire-string} :card [field-kw …] :right <action> :left
  <action>} — judged against one kind's normalized rdef. The one
  battery both gates run: the declaration-time check (check-views,
  which errs on the first) and the saved_view write gate (which
  denies with all of them) — a user-authored view obeys exactly the
  law a declared one does. Returns problem strings, empty when the
  view composes only declared primitives."
  [r v]
  (-> []
      (into (when-not (contains? view-kinds (:kind v))
              [(str ":kind is :deck or :feed, got " (pr-str (:kind v)))]))
      (into (when (contains? v :where)
              (if-not (and (map? (:where v)) (seq (:where v)))
                [":where is a non-empty {field value} map"]
                (view-where-problems r (:where v)))))
      (into (when (contains? v :card)
              (if-not (and (vector? (:card v)) (seq (:card v))
                           (every? keyword? (:card v)))
                [":card is a non-empty vector of field keywords"]
                (concat
                 (when-some [bad (seq (remove (data-keys r) (:card v)))]
                   [(str ":card names " (vec bad)
                         ", not data field(s) of the schema")])
                 ;; a prose field rides rows only when it opts in with
                 ;; :x-display {:teaser true} — a card naming one
                 ;; without the flag would render silently blank, so
                 ;; the gate says it out loud instead
                 (let [entries (schema/entry-map (:schema r))]
                   (for [f (:card v)
                         :let [props (:properties (get entries f))]
                         :when (and (= "prose" (get-in props
                                                       [:x-display :widget]))
                                    (not (get-in props
                                                 [:x-display :teaser])))]
                     (str ":card names " f ", a prose field without "
                          ":x-display {:teaser true} — flag it or the "
                          "card cell stays blank")))))))
      (into (case (:kind v)
              :feed
              (for [side [:right :left]
                    :when (contains? v side)]
                (str "is a :feed — a sequential read takes no " side
                     " gesture"))
              :deck
              (let [where (:where v)]
                (cond
                  (not (map? where))
                  [(str "is a :deck with no :where — a deck is a queue, "
                        "and a queue names what belongs in it")]

                  (not (contains? where :state))
                  [(str ":where must constrain :state — a deck drains by "
                        "moving cards out of its own states")]

                  :else
                  (let [where-states (into #{}
                                           (map str/trim)
                                           (str/split (str (get where :state))
                                                      #","))]
                    (mapcat (fn [side]
                              (if-not (contains? v side)
                                [(str "is a :deck and declares no " side
                                      " — both gestures are required")]
                                (deck-gesture-problems r side (get v side)
                                                       where-states)))
                            [:right :left]))))
              nil))))

(defn- check-views
  "The declared :views battery: names are unique snake tokens, and
  each view passes the shared view-problems core (kind token, :where
  expressibility, :card ⊆ schema, the :deck queue rules, a :feed's
  refusal of gestures). Runs post-normalize, where :undo has already
  stamped [:safety :reversible]."
  [r]
  (when-some [views (:views r)]
    (when-not (and (vector? views) (every? map? views) (seq views))
      (err r :views ":views is a non-empty vector of view maps"))
    (when-some [dup (->> (map :name views) frequencies
                         (keep (fn [[n c]] (when (< 1 c) n))) seq)]
      (err r :views (str "view names must be unique — " (vec dup)
                         " declared more than once (view= picks by name)")))
    (doseq [v views
            :let [vname (:name v)]]
      (when-not (and (keyword? vname) (re-matches snake (name vname)))
        (err r :views (str "every view declares a snake_case keyword :name, got "
                           (pr-str vname))))
      (when (and (:display v) (not (map? (:display v))))
        (err r :views (str "view " vname " :display is a map")))
      (when-some [p (first (view-problems r v))]
        (err r :views (str "view " vname " " p))))))

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
    (doseq [ld (:links r)]
      (when (:badge ld)
        (when-not (contains? dkeys (:badge ld))
          (err r :links (str "link " (:rel ld) ": badge " (:badge ld)
                             " is not a data field — the badge renders the "
                             "instance's current value of that field"))))
      ;; :embed true takes every default; the map form only exists to
      ;; override :limit/:max-limit, so a stray non-positive or
      ;; inverted pair is refused here rather than misbehaving quietly
      ;; at request time
      (when (map? (:embed ld))
        (let [{:keys [limit max-limit]} (:embed ld)]
          (doseq [[k v] [[:limit limit] [:max-limit max-limit]]
                  :when (some? v)]
            (when-not (and (int? v) (pos? v))
              (err r :links (str "link " (:rel ld) ": :embed " k " " (pr-str v)
                                 " must be a positive int"))))
          (when (and limit max-limit (> limit max-limit))
            (err r :links (str "link " (:rel ld) ": :embed :limit " limit
                               " exceeds its own :max-limit " max-limit))))))))

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

(defn- check-aggregate-spec
  "One aggregate spec (batch C generalizes phase 6's count check to
  the pair): {:count|:sum {:related <edge> | :owns <child-kind>,
  :where {field #{values}}, (:sum only) :of <field>, :when-empty
  :absent}} — exactly one edge, a shaped where, no :over (an
  aggregate's inputs ARE its edge). A count fact is an :int; a sum
  fact is :int or :decimal and MUST name :of, the summed target
  field. :when-empty :absent (sum only) lands nil when the sum has no
  contributions — no information is not a zero — and requires the
  fact's schema to accept nil ([:maybe …]). The edge's (and :of's)
  existence is an assembly question (checks-assembly)."
  [r fact d agg-key]
  (let [c (get d agg-key)
        an (name agg-key)
        allowed (if (= :sum agg-key) #{:related :owns :where :of :when-empty}
                    #{:related :owns :where})]
    (when (seq (:over d))
      (err r :derived (str "derived field " fact ": a " an "'s inputs are its "
                           "declared edge; :over does not apply")))
    (when-not (and (map? c)
                   (= 1 (count (select-keys c [:related :owns])))
                   (every? allowed (keys c))
                   (every? keyword? (vals (select-keys c [:related :owns]))))
      (err r :derived (str "derived field " fact ": :" an " is {:related <edge> "
                           "| :owns <child-kind>, :where {field #{values}}"
                           (when (= :sum agg-key)
                             ", :of <field>, :when-empty :absent")
                           "} — exactly one edge")))
    (when (contains? c :where)
      (let [w (:where c)]
        (when-not (and (map? w) (seq w)
                       (every? keyword? (keys w))
                       (every? #(and (coll? %) (not (map? %)) (seq %)) (vals w)))
          (err r :derived (str "derived field " fact ": :" an " :where is a "
                               "{field #{values}} map of non-empty value sets")))))
    (if (= :sum agg-key)
      (do
        (when-not (keyword? (:of c))
          (err r :derived (str "derived field " fact ": :sum names :of, the "
                               "summed target field")))
        (when-not (contains? #{:int :decimal}
                             (schema-head (schema/field-schema (:schema r) fact)))
          (err r :derived (str "derived field " fact ": a sum fact is numeric — "
                               "declare " fact " as :int or :decimal in the "
                               "schema")))
        (when (contains? c :when-empty)
          (when-not (= :absent (:when-empty c))
            (err r :derived (str "derived field " fact ": :sum :when-empty has "
                                 "one spelling, :absent (a sum with no "
                                 "contributions lands nil); omit it for the "
                                 "0-over-empty default")))
          (let [s (:schema (get (schema/entry-map (:schema r)) fact))]
            (when-not (and (vector? s) (= :maybe (first s)))
              (err r :derived (str "derived field " fact ": :when-empty :absent "
                                   "lands nil — declare " fact " as [:maybe …] "
                                   "in the schema"))))))
      (when-not (= :int (schema-head (schema/field-schema (:schema r) fact)))
        (err r :derived (str "derived field " fact ": a count fact is an int — "
                             "declare " fact " as :int in the schema"))))))

(defn- check-derived [r]
  (let [derived (:derived r)
        dkeys (data-keys r)]
    (doseq [[fact d] (sort-by key derived)]
      (when-not (= 1 (count (filter #(contains? d %) [:expr :count :sum])))
        (err r :derived (str "derived field " fact ": declare exactly one of "
                             ":expr (an expression fact), :count, or :sum (an "
                             "aggregate over a declared edge)")))
      (doseq [agg [:count :sum]
              :when (contains? d agg)]
        (check-aggregate-spec r fact d agg))
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

(defn- check-answered-at-a-door
  "The states in which words are not an answer (waymark-vf8) validate
  hard, because a typo here refuses NOBODY and prints no error: the
  remark wall reads this map off the SUBJECT's declaration, and a
  clause naming a state this kind does not have, or a door it does not
  serve, is a wall that quietly never fires.

  Every key is a declared state; every clause names a `:door` this
  kind declares as an action, a `:whose` field its schema declares,
  and an `:explain` sentence carrying `{door}` — the address the
  framework fills in, which is the whole reason the refusal can send
  the reader somewhere rather than to source."
  [r]
  (let [m (:answered-at-a-door r)]
    (when (some? m)
      (when-not (map? m)
        (err r :answered-at-a-door
             ":answered-at-a-door is {state {:door … :whose … :explain …}}"))
      (let [states (set (:states r))
            fields (set (keys (schema/entry-map (:schema r))))]
        (doseq [[st clause] (sort-by (comp str key) m)]
          (when-not (contains? states st)
            (err r :answered-at-a-door
                 (str "state " st " is not one this kind declares — a clause "
                      "on a state no row can be in is a wall that never "
                      "fires")))
          (when-not (map? clause)
            (err r :answered-at-a-door
                 (str st " declares " (pr-str clause)
                      "; a clause is {:door … :whose … :explain …}")))
          (when-some [unknown (seq (sort (remove #{:door :whose :explain}
                                                 (keys clause))))]
            (err r :answered-at-a-door
                 (str st " declares unknown key(s) " (vec unknown)
                      "; a clause speaks [:door :explain :whose]")))
          (let [{:keys [door whose explain]} clause]
            (when-not (contains? (:actions r) door)
              (err r :answered-at-a-door
                   (str st " names the door " (pr-str door)
                        ", which is no action of this kind — the refusal "
                        "sends the reader to an address that would 404")))
            (when-not (contains? fields whose)
              (err r :answered-at-a-door
                   (str st " names :whose " (pr-str whose)
                        ", which this kind's schema does not declare — the "
                        "wall could never tell whose row it is")))
            (when (or (not (string? explain)) (str/blank? explain))
              (err r :answered-at-a-door
                   (str st " carries no :explain — the household's own "
                        "sentence is what a refused reader hits")))
            (when-not (str/includes? (str explain) "{door}")
              (err r :answered-at-a-door
                   (str st "'s :explain does not say {door} — a refusal "
                        "that does not name the address is a refusal that "
                        "sends the reader to read source")))))))))

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

(defn- check-defaults
  "A declared :default must be a value its own entry schema accepts —
  a default the door would refuse is a lie at the def site — and a
  derived field takes no default (one fact, one writer). Walks the
  data schema, the create schema, and every action input, one level
  into vector-of-map items (the same depth apply-defaults fills)."
  [r]
  (let [validate
        (fn validate [where form]
          (doseq [[k {:keys [properties schema]}] (schema/entry-map form)
                  :let [s (if (and (vector? schema) (= :maybe (first schema)))
                            (second schema)
                            schema)
                        item (when (and (vector? s) (= :vector (first s)))
                               (last s))]]
            (when (contains? properties :default)
              (when (contains? (:derived r) k)
                (err r :defaults (str where "." (name k) ": a derived field "
                                      "takes no default — one fact, one writer")))
              (when-not (schema/validate schema (:default properties))
                (err r :defaults (str where "." (name k) ": default "
                                      (pr-str (:default properties))
                                      " is not a value this field accepts"))))
            (when (and (vector? item) (= :map (first item)))
              (validate (str where "." (name k)) item))))]
    (validate "data" (:schema r))
    (when-some [cs (:create-schema r)] (validate "create" cs))
    (doseq [a (machine/actions-seq r)
            :when (:input a)]
      (validate (str "action " (name (:name a)) " input") (:input a)))
    nil))

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
          check-handler-signatures check-opaque-residue
          check-summary-template check-waive-tokens
          check-place check-edit check-altitude check-long-text
          check-options
          check-filterable check-sortable check-default-filters
          check-faceted check-views check-oneof check-unique check-links
          check-derived check-renames check-unless check-require
          check-defaults check-answered-at-a-door])})
