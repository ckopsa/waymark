(ns waymark10.declare
  "Declaration ergonomics: def the pieces, keep one law.

  defaction and defderived def PLAIN maps — the very value the inline
  spelling would put in the resource map — with construction
  validation run eagerly, so a malformed def fails at its own line
  instead of at some distant defresource. What must wait, waits: the
  cross-referencing checks (states exist, judged fields are input
  fields, the derived fact is a schema field, cycles) still run at
  defresource, because only the assembled declaration can answer
  them. The def site validates exactly what it can see.

  Because the def'd value IS the inline value, a def'd piece is usable
  everywhere the inline spelling is: directly in :actions / :derived,
  or colocated on a schema entry ([:end_date {:derived end-date} …]) —
  and the fingerprint cannot tell the spellings apart (two spellings,
  one law; batch_g_invariance_test pins it).

  Blessed idioms (docs/waymark10-g-notes.md):
  - action groups: a var holding a map of actions, merged into
    :actions — (merge review-actions {:archive …}).
  - named safety values: (def routine {:idempotent true …}) cited as
    :safety routine. Reference is explicit declaration, not inference:
    the safety map is still spelled once, in full, and every citation
    names it — no property is ever computed from behavior, so
    safety-never-inferred holds.
  - local builder fns returning plain maps, when a family of actions
    differs by a parameter.

  Batch H adds the typed field words (one-of, money, percent, date,
  flag, quantity, prose, ref, measured-by, and described — the prose
  wrapper any of them wears) — plain functions returning
  the exact malli forms the inline spelling writes, entry properties
  riding as namespaced metadata the :fields reader hoists — and the
  sentence-first defguard: (defguard name (refuse \"sentence\") '(law)).
  Every word normalizes at the declaration gate into the same plain
  map the split spelling writes by hand; the fingerprint cannot tell
  the spellings apart."
  (:refer-clojure :exclude [ref])
  (:require [clojure.string :as str]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.holds :as holds]
            [waymark10.resource :as r]
            [waymark10.scenario :as scenario]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

;; ── actions ─────────────────────────────────────────────────────────

(defn action
  "Construction-validate an action map eagerly — the same
  normalize-action the defresource gate runs — and return the PLAIN
  map, identical to the inline spelling. The normalized value is
  discarded: normalization is the seam, and it happens once, at
  defresource. aname labels the refusal."
  [aname a]
  (r/normalize-action :defaction aname a)
  a)

(defmacro defaction
  "Def an action as a plain map, construction-validated at the def
  site:

     (defaction assign-meal
       {:from #{:draft} :to :draft
        :safety {:idempotent true :reversible false :confirm false}
        …})

  An inline (fn …) :handler gets its canonical printed form captured
  as :waymark10/form metadata — the same identity defhandler mints —
  so the fingerprint hashes the law, never the object. The form is
  gensym-canonicalized on the way in (waymark-j82): a `#(…)` in the
  body reads as `(fn* [p1__29157#] …)`, and that counter belongs to
  the load, not to the law."
  [name amap]
  (let [amap (if (and (map? amap)
                      (seq? (:handler amap))
                      (= 'fn (first (:handler amap))))
               (assoc amap :handler
                      `(with-meta ~(:handler amap)
                         {:waymark10/form
                          '~(expr/canonical-gensyms (:handler amap))}))
               amap)]
    `(def ~name (action ~(keyword name) ~amap))))

;; ── derivations ─────────────────────────────────────────────────────

(defn derived
  "Normalize and scope-validate one derived spec at construction:
  exactly one of :expr/:count, the expression's structural problems,
  and the :over scope — :over is right there, so an out-of-scope
  (var …) fails at the def line. Whether the fact is a schema field,
  whether the edge exists, whether facts cycle — defresource's and
  assembly's questions. Returns the normalized spec: the same value
  normalize-resource lands the inline spelling on."
  [dname spec]
  (let [err (fn [msg]
              (throw (t/definition-error
                      (str "defderived " (name dname) ": " msg))))]
    (when (not= 1 (count (filter #(contains? spec %) [:expr :count :sum])))
      (err (str "declare exactly one of :expr (an expression fact), "
                ":count, or :sum (aggregates over a declared edge)")))
    (when (and (or (contains? spec :count) (contains? spec :sum))
               (seq (:over spec)))
      (err "an aggregate's inputs are its declared edge; :over does not apply"))
    (let [spec (r/normalize-derived-spec spec)]
      (when-some [e (:expr spec)]
        (when-some [p (first (concat (expr/problems e)
                                     (expr/derived-scope-problems
                                      e (set (:over spec)))))]
          (err p)))
      spec)))

(defmacro defderived
  "Def a derived spec, normalized and scope-validated at the def site:

     (defderived end-date
       {:over [:start_date :weeks]
        :expr '(+ (var :start_date) (days (- (* 7 (var :weeks)) 1)))})

  The def'd value drops into :derived {:end_date end-date} or onto its
  own schema entry ([:end_date {:derived end-date} …]) — one law,
  wherever it is spelled."
  [name spec]
  `(def ~name (derived ~(keyword name) ~spec)))

;; ── typed field words (batch H) ─────────────────────────────────────
;; Each word is a plain function returning the exact malli form the
;; inline spelling writes. Entry properties (:kind on a ref, the
;; prose/money/percent :x-display) and editor policy (a prose draft)
;; ride as namespaced METADATA — a keyword cannot carry meta, so a
;; word with properties wraps its form in a one-element vector the
;; :fields reader unwraps; the FORM the schema compiles is identical
;; either way, so the fingerprint cannot tell the spellings apart.
;; Inside :fields rows the metadata is hoisted onto the entry; in a
;; plain :schema, spell the entry properties yourself.

(defn- werr [msg]
  (throw (t/definition-error msg)))

(defn- word
  [form m]
  (if (empty? m)
    form
    (with-meta (if (keyword? form) [form] form) m)))

(defn one-of
  "A closed selection: (one-of :dollars :shares :pct) →
  [:enum \"dollars\" \"shares\" \"pct\"] — tokens cross the wire as
  strings, so the enum speaks strings."
  [& values]
  (when (or (< (count values) 2) (not (every? keyword? values)))
    (werr "one-of takes two or more keyword values — one value is a constant, none is a typo"))
  (into [:enum] (map name) values))

(defn date
  "A calendar date: :waymark/date (LocalDate in the law, ISO on the
  wire)."
  []
  :waymark/date)

(defn flag
  "A yes/no fact: :boolean."
  []
  :boolean)

(defn quantity
  "A non-negative exact count-like amount (shares, units): a :decimal
  with :min 0 — exact, never a float."
  []
  [:decimal {:min 0}])

(defn money
  "An exact monetary amount: :decimal (the E.lit(\"0.02\") lesson —
  never floats), the currency riding as display advertisement."
  [currency]
  (when-not (keyword? currency)
    (werr "money names its currency as a keyword: (money :usd)"))
  (word [:decimal]
        {:waymark10/props {:x-display {:widget "money"
                                       :currency (name currency)}}}))

(defn percent
  "An exact percentage, 0–100: [:decimal {:min 0 :max 100}]."
  []
  (word [:decimal {:min 0 :max 100}]
        {:waymark10/props {:x-display {:widget "percent"}}}))

(defn prose
  "Free text with the prose widget declared (design §10's knowledge
  floor). An optional leading string is the field's label; an optional
  options map {:shared bool :live bool} declares the generated
  editor's draft policy — the same one concept meal.update_recipe
  spells as :edit {:draft {:shared true :live true}}."
  [& args]
  (let [[label args] (if (string? (first args))
                       [(first args) (rest args)]
                       [nil args])
        [opts extra] [(first args) (rest args)]]
    (when (or (seq extra) (and opts (not (map? opts))))
      (werr "prose takes an optional label string and an optional {:shared … :live …} map"))
    (when-some [unknown (seq (remove #{:shared :live} (keys opts)))]
      (werr (str "prose options are :shared and :live; " (vec unknown)
                 " declare nothing")))
    (word [:string {:min 1 :max 8000}]
          (cond-> {:waymark10/props
                   {:x-display (cond-> {:widget "prose"}
                                 label (assoc :label label))}}
            (seq opts) (assoc :waymark10/edit
                              {:draft {:shared (boolean (:shared opts))
                                       :live (boolean (:live opts))}})))))

(defn ref
  "A cross-resource reference: :waymark/ref with its target declared
  ({:kind …}, exactly what plan.days.meal_id spells) — the picker,
  the navigable reference, and the assembly ref-check all read the
  one declaration. An optional {:label :fund_name} names the field
  the engine maintains the target's label into; an optional
  {:carry {:notes :chore_notes}} names further target data fields
  the engine copies alongside (the label doctrine — generated, never
  hand-copied — for facts the referring row wants at a glance; same
  freshness scope: stamped at the write that sets the ref).

  A member ref may say who may be named (docs/spec-teams.md §3):
  {:role \"pt\"}, a role the member holds on their row or through an
  active team, and {:in-team '(data :crew)}, an expression naming the
  team they must be on. The registry writes the guards that judge them
  (waymark10.qualifies)."
  [kind & [{:keys [label carry role in-team] :as opts}]]
  (when-not (keyword? kind)
    (werr "ref names its target kind as a keyword: (ref :fund)"))
  (when (and (or (some? role) (contains? opts :in-team)) (not= :member kind))
    (werr "ref :role and :in-team say who may be named, so they ride a member ref: (ref :member {:role \"pt\"})"))
  (when (and (some? role) (not (and (string? role) (not (str/blank? role)))))
    (werr "ref :role is the role name the member holds: (ref :member {:role \"pt\"})"))
  (when (and (contains? opts :in-team)
             (not (string? in-team))
             (or (not (seq? in-team)) (seq (expr/problems in-team))))
    (werr "ref :in-team is a team's name or an expression naming one: (ref :member {:in-team '(data :crew)})"))
  (when (and carry
             (not (and (map? carry) (seq carry)
                       (every? keyword? (keys carry))
                       (every? keyword? (vals carry)))))
    (werr "ref :carry is a non-empty {source-field target-field} map of keywords"))
  (word [:waymark/ref]
        {:waymark10/props (cond-> {:kind kind}
                            label (assoc :label label)
                            carry (assoc :carry carry)
                            role (assoc :role role)
                            (contains? opts :in-team) (assoc :in-team in-team))}))

(defn measured-by
  "A discriminated amount: the field's unit of measure is a sibling
  field, and the amount must fit the arm that sibling selects —
  (measured-by :value_type {:dollars (money :usd) :shares (quantity)
  :pct (percent)}). Recorded gap: the schema layer validates one
  entry's VALUE, so the sibling-dispatched union cannot live in the
  malli form; the stored form is the arms' one shared scalar family
  (:decimal), the arm map rides as display advertisement, and the
  cross-field law lands as a generated check on the group's editor
  (waymark10.resource/measured-guard) — refused at the write, not
  unrepresentable. A true :multi entry spelling is a recorded demand."
  [by arms]
  (when-not (and (keyword? by) (map? arms) (seq arms)
                 (every? keyword? (keys arms)))
    (werr "measured-by takes the measuring sibling field and a {measure-value (word …)} map"))
  (let [arm-forms (into (sorted-map)
                        (map (fn [[k w]] [(name k) (r/word-form w)]))
                        arms)
        head (fn [f] (let [f (if (vector? f) (first f) f)] f))]
    (when-not (every? #(= :decimal (head %)) (vals arm-forms))
      (werr (str "measured-by arms must share the exact-decimal family "
                 "(money/quantity/percent); got "
                 (pr-str (vec (distinct (map head (vals arm-forms)))))
                 " — a mixed-family union is a recorded demand")))
    (word [:decimal]
          {:waymark10/props
           {:x-display {:widget "measured"
                        :measured-by
                        ;; plain maps here (equality is order-blind, and
                        ;; a sorted map would trip keyword lookups in the
                        ;; JSON-Schema walk); the GUARD's arm map is the
                        ;; sorted one — its printed form is law
                        {:by (name by)
                         :arms (into {}
                                     (map (fn [[k w]]
                                            [(name k)
                                             (or (:x-display (r/word-props w)) {})]))
                                     arms)}}}
           :waymark10/measured {:field nil :by by :arms arm-forms}})))

(defn described
  "A field word wearing the household's own prose: the :x-display map
  every client renders above and below the box, and the MCP tool
  description reads as the argument's explanation.

     [:capacity (described (one-of :high_focus :low_focus :exhausted)
                           {:label \"How much you have in you\"
                            :choices {\"high_focus\" \"Sharp — …\" …}})]

  A plain :schema entry has a properties slot and spells :x-display
  itself; a :fields row is strictly [field (word …)], so this is where
  a lifecycle-group field says its sentence (waymark-ts2). The merge is
  key by key over whatever :x-display the word already declares, so
  (described (prose \"How it went\") {:help \"…\"}) keeps both the prose
  widget and the label the word named.

  The malli FORM is untouched — entry properties sit in no fingerprint
  facet, so a described word and a bare one are the same law."
  [w display]
  (when-not (map? display)
    (werr "described takes a field word and an :x-display map: (described (flag) {:label \"…\"})"))
  (let [form (r/word-form w)
        props (r/word-props w)]
    (with-meta (if (keyword? form) [form] form)
      (assoc (meta w)
             :waymark10/props
             (assoc props :x-display (merge (:x-display props) display))))))

;; ── sentence-first guards (batch H) ─────────────────────────────────
;; (defguard blocking-items-reviewed
;;   (refuse \"Every compliance-class checklist item is reviewed —
;;            {open_blocking} remain.\")
;;   '(zero? (var :open_blocking)))
;;
;; The def'd value IS the plain expression-guard map g/expr builds —
;; pure data, so the def'd and inline spellings are one value and one
;; fingerprint. Three authored conveniences desugar at the def line,
;; before validation and normalization:
;;   (var :fact)   → (data :fact)   — in a guard, a bare fact name
;;                                    reads the row's stored document
;;   (zero? e)     → (= 0 e)
;;   (present? :a :b) → (and (is-set (data :a)) (is-set (data :b)))
;; and every {placeholder} in the sentence that the law's own input
;; reads do not already cover lands as (data :placeholder) garnish —
;; the same :vars the split spelling writes by hand.

(defn refuse
  "The blocking sentence of a defguard: severity :refuse. A second
  argument is the way out the refusal carries: {:remedies [:kind/action
  …]}, the doors that change the verdict, or {:open \"…\"}, the
  sentence that says no door does."
  ([sentence] (refuse sentence nil))
  ([sentence way-out]
   (when (or (not (string? sentence)) (str/blank? sentence))
     (werr "refuse takes the refusal sentence — a guard explains itself"))
   (when-not (and (or (nil? way-out) (map? way-out))
                  (every? #{:remedies :open} (keys way-out)))
     (werr (str "refuse's way out is {:remedies [:kind/action …]} or "
                "{:open \"…\"}; " (pr-str way-out) " is neither")))
   (merge {:severity :refuse :explain sentence} way-out)))

(defn warn
  "The advisory sentence of a defguard: severity :warning, always
  acknowledgable by guard name (E1 — the :acknowledge-by-name flag is
  accepted as documentation of that standing protocol; it changes
  nothing)."
  [sentence & flags]
  (when (or (not (string? sentence)) (str/blank? sentence))
    (werr "warn takes the warning sentence — a guard explains itself"))
  (doseq [f flags]
    (when-not (= :acknowledge-by-name f)
      (werr (str "warn accepts only the :acknowledge-by-name flag; "
                 (pr-str f) " declares nothing"))))
  {:severity :warning :explain sentence})

(defn hold
  "The person-tap sentence of a defguard: the door waits for a person.
  An agent's call is held as a held_call, and the person's Allow runs
  it; a person's own call goes through. The law after it is optional:
  with one, the door holds only while the law is false.

  `(hold \"…\" :for :role \"x\")` names whose tap it waits for when it
  is not the agent's own person: the held call waits for a member
  holding role x (a seed's cast roles), and the Allow door refuses a
  person without it.

  `(hold \"…\" :by '(data :therapist))` ADDRESSES it (docs/spec-teams.md
  §5): the expression names a member or a team from the row and the
  input, every caller who is not that member (or on that team) is held,
  a person as well as an agent, and only the addressee may Allow. With
  :for :role as well, the addressee must hold the role."
  [sentence & more]
  (when (or (not (string? sentence)) (str/blank? sentence))
    (werr "hold takes the sentence the held call shows — a guard explains itself"))
  (let [said (loop [m {} xs more]
               (let [[k a b] xs]
                 (cond
                   (empty? xs) m
                   (and (= :for k) (= :role a) (string? b) (not (str/blank? b))
                        (not (contains? m :for-role)))
                   (recur (assoc m :for-role b) (drop 3 xs))
                   (and (= :by k) (next xs) (not (contains? m :by)))
                   (recur (assoc m :by a) (drop 2 xs))
                   :else
                   (werr (str "hold takes only its sentence, then :for :role \"…\" "
                              "and :by '(expr), each at most once; " (pr-str more)
                              " declares nothing")))))
        by (some-> (:by said) expr/normalize)]
    (when (and (contains? said :by)
               (or (not (seq? (:by said))) (seq (expr/problems by))))
      (werr (str "hold :by names its addressee with a quoted expression, "
                 ":by '(data :therapist); " (pr-str (:by said)) " is not one")))
    (cond-> {:severity :refuse :explain sentence :hold true}
      (:for-role said) (assoc :for-role (:for-role said))
      by (assoc :by by))))

(defn- rewrite-sentence-law
  "The defguard-gate desugars, applied before g/expr validates: the
  result is a form already inside the expression vocabulary."
  [form]
  (cond
    (seq? form)
    (let [[op & args] form]
      (cond
        (= 'var op)
        (list 'data (first args))

        (= 'zero? op)
        (do (when (not= 1 (count args))
              (werr "(zero? …) takes exactly one expression"))
            (list '= 0 (rewrite-sentence-law (first args))))

        (= 'present? op)
        (do (when (or (empty? args) (not (every? keyword? args)))
              (werr "(present? …) takes one or more field-name keywords"))
            (cons 'and (map (fn [f] (list 'is-set (list 'data f))) args)))

        :else (cons op (map rewrite-sentence-law args))))

    (vector? form) (mapv rewrite-sentence-law form)
    :else form))

(def ^:private sentence-placeholder #"\{([A-Za-z0-9_]+)\}")

(declare law-guard hold-guard)

(defn sentence-guard
  "Build the guard map a sentence + law pair means: the plain
  expression guard for (refuse …) and (warn …), and the person-tap
  code guard for (hold …), whose law is optional. Construction-
  validated here (the defaction pattern): the def line is where a
  malformed guard fails."
  [gname clause law & [opts]]
  (when-not (and (map? clause) (#{:refuse :warning} (:severity clause))
                 (string? (:explain clause)))
    (throw (t/definition-error
            (str "defguard " (name gname)
                 ": the first argument is the sentence — (refuse \"…\"), (warn \"…\") or (hold \"…\")"))))
  (if (:hold clause)
    (hold-guard gname clause law opts)
    (law-guard gname clause law opts)))

(defn- law-guard
  "The plain expression guard a refuse or warn sentence and its law mean."
  [gname clause law opts]
  (when-not (seq? law)
    (throw (t/definition-error
            (str "defguard " (name gname)
                 ": a guard is a verdict — give it its law: "
                 "(defguard " (name gname) " (refuse \"…\") '(expr))"))))
  (when-not (and (or (nil? opts) (map? opts))
                 (every? #{:remedies :open :errors :evidence :vars :hidden :pending} (keys opts))
                 (contains? #{nil :allow :deny} (:pending opts)))
    (throw (t/definition-error
            (str "defguard " (name gname)
                 ": the map after the law is {:remedies … :open … :errors {field \"…\"}"
                 " :vars {name expr} :hidden {binder \"…\"} :pending :allow|:deny}; "
                 (pr-str opts) " is not"))))
  (when-some [vs (:vars opts)]
    (when-not (and (map? vs) (every? keyword? (keys vs)) (every? seq? (vals vs)))
      (throw (t/definition-error
              (str "defguard " (name gname)
                   ": :vars is {name '(expr)}, each read by {name} in the sentence")))))
  (when-some [hs (:hidden opts)]
    (let [binders (into (set (keys (expr/rows-binders law)))
                        (when (map? (:evidence opts)) (keys (:evidence opts))))]
      (when-not (and (map? hs)
                     (every? binders (keys hs))
                     (every? #(and (string? %) (not (str/blank? %))) (vals hs)))
        (throw (t/definition-error
                (str "defguard " (name gname)
                     ": :hidden is {binder \"sentence\"}, a binder of the law: "
                     "the sentence its {binder.field} part reads as when no match is visible"))))))
  (let [clause (merge clause (dissoc opts :vars :hidden))
        w (rewrite-sentence-law law)
        inputs (:inputs (expr/info (expr/normalize w)))
        own (into {} (map (fn [[k f]] [k (rewrite-sentence-law f)])) (:vars opts))
        vars (into {}
                   (keep (fn [[_ p]]
                           (let [k (keyword p)]
                             (when-not (or (contains? inputs k) (contains? own k))
                               [k (list 'data k)]))))
                   (re-seq sentence-placeholder (:explain clause)))
        vars (merge vars own)]
    (g/expr (cond-> {:name gname
                     :when w
                     :explain (:explain clause)
                     :severity (:severity clause)}
              (seq vars) (assoc :vars vars)
              (:remedies clause) (assoc :remedies (:remedies clause))
              (:open clause) (assoc :open (:open clause))
              (:errors clause) (assoc :errors (:errors clause))
              (:evidence clause) (assoc :evidence (:evidence clause))
              (:hidden opts) (assoc :hidden (:hidden opts))
              ;; the default, :allow, is left out, so no fingerprint moves
              (= :deny (:pending clause)) (assoc :pending :deny)))))

(def ^:private hold-open
  "No door clears this one. The call waits as a held_call for the person's tap, and the person's Allow runs it.")

(defn- look
  "A read hook's answer, or nil when it has no such kind or row."
  [f]
  (try (f) (catch Exception _ nil)))

(defn- addressee-of
  "Who a (hold … :by expr) names on this row: {:member id :name …} or
  {:team id :name …}, a value no row answers as the member it names,
  and nil when the expression names nobody."
  [by row inp ctx]
  (let [v (expr/evaluate by (g/expr-scope row inp ctx))
        a (when-not (expr/unknown? v)
            (some-> v (#(if (keyword? %) (name %) (str %))) str/trim not-empty))
        read' (:read ctx)
        find' (:find ctx)]
    (when a
      (or (when-some [m (when read' (look #(read' :member a)))]
            {:member (str (:id m))
             :name (or (some-> (get-in m [:data :display]) str not-empty) (str (:id m)))})
          (when-some [tm (when read'
                           (or (look #(read' :team a))
                               (when find' (first (look #(find' :team {:name a} {:limit 1}))))))]
            {:team (str (:id tm))
             :name (or (some-> (get-in tm [:data :name]) str not-empty) (str (:id tm)))})
          {:member a :name a}))))

(defn- addressed-to?
  "Is the member `id` the addressee, or on the addressee team?"
  [ad ctx id]
  (let [id (some-> id str not-empty)]
    (boolean
     (when (and ad id (not (str/starts-with? id "seat:")))
       (if (:team ad)
         (when-some [in? (:in-team? (g/team-reads ctx))] (in? id (:team ad)))
         (= id (:member ad)))))))

(defn- hold-guard
  "The code guard a (hold \"…\") sentence means: the person-wall shape
  inbox_item's `the-correction-is-a-persons` spells by hand. `:hold
  true`, reads :principal and :within; every hand but an agent's
  passes, and an agent passes only as the engine's replay of a held
  call its person allowed, for the kind and door the call stands on
  (the ctx's :self and :door). With a law, the hold stands only while
  the law is false; the law true, every hand passes."
  [gname clause law opts]
  (when-not (or (nil? law) (seq? law))
    (throw (t/definition-error
            (str "defguard " (name gname)
                 ": a hold's law is a quoted expression, '(expr), or nothing"))))
  (when-not (and (or (nil? opts) (map? opts)) (every? #{:open} (keys opts)))
    (throw (t/definition-error
            (str "defguard " (name gname)
                 ": the map after a hold's law is {:open \"…\"}; " (pr-str opts) " is not"))))
  (let [sentence (:explain clause)
        role (:for-role clause)
        by (:by clause)
        field (when (and (seq? by) (= 'data (first by)) (keyword? (second by)))
                (name (second by)))
        inner (when law (law-guard gname {:severity :refuse :explain sentence} law nil))
        ;; a role hold says whose tap it waits for as its evidence, and
        ;; an addressed one whom; the router writes it on the held
        ;; call's door
        held (fn [m ev]
               (let [ev (cond-> (or ev {}) role (assoc :role role))]
                 (t/deny (cond-> m (seq ev) (assoc :evidence ev)))))
        ;; spec-teams §5: an addressee that is nobody, or that lacks the
        ;; role the hold names, refuses — the router does not hold it
        refused (fn [why] (t/deny {:evidence {:refused why}}))
        addressed (fn [row inp ctx v self door]
                    (let [ad (addressee-of by row inp ctx)
                          holds? (:holds-role? (g/team-reads ctx))
                          h (when (and (:kind self) door)
                              (holds/allowed-hold ctx (:kind self) door (:id row)))]
                      (cond
                        (nil? ad)
                        (refused (str "No " (or field "addressee") " is named, so nobody can sign."))
                        (and role (:member ad) holds? (not (holds? (:member ad) role)))
                        (refused (str (:name ad) " does not hold the " role " role, so "
                                      "nobody named here may approve this; give the "
                                      (or field "addressee") " the role an approver must hold."))
                        (addressed-to? ad ctx (get-in ctx [:principal :id])) (t/allow)
                        ;; the replay judges the addressee again: the
                        ;; person who allowed must be the one named now
                        (and h (addressed-to? ad ctx (get-in h [:data :decided_by]))) (t/allow)
                        :else (held (if (seq (:vars v)) {:vars (:vars v)} {})
                                    (cond-> (select-keys ad [:member :team :name])
                                      field (assoc :field field))))))
        check (fn [row inp ctx]
                (let [self (:self ctx)
                      door (or (:door ctx) (:action self))
                      [v] (when inner (g/evaluate inner row inp ctx))]
                  (cond
                    (and v (t/allow? v)) v
                    by (addressed row inp ctx v self door)
                    (not= :agent (:type (:principal ctx))) (t/allow)
                    (and (:kind self) door
                         (holds/approved-hold? ctx (:kind self) door (:id row))) (t/allow)
                    (seq (:vars v)) (held {:vars (:vars v)} nil)
                    :else (held {} nil))))]
    (g/guard (cond-> {:name gname
                      :explain sentence
                      :hold true
                      :reads (into [:principal :within]
                                   (remove #{:principal :within})
                                   (:reads inner))
                      :open (or (:open opts)
                                (if role
                                  (str "No door clears this one. The call waits as a held_call for "
                                       "a member holding the " role " role, and their Allow runs it.")
                                  hold-open))
                      :check (with-meta check {:waymark10/form (if by
                                                                 (list 'hold sentence law :by by)
                                                                 (list 'hold sentence law))})}
               role (assoc :hold-role role)
               by (assoc :hold-by by)
               (seq (:judges inner)) (assoc :judges (:judges inner))
               (seq (:vars inner)) (assoc :vars (vec (keys (:vars inner))))))))

(defmacro defguard
  "A sentence-first expression guard:

     (defguard fields-complete
       (refuse \"A transaction goes to review with its value type,
                amount, and effective date set.\")
       '(present? :value_type :amount :effective_date))

  Def's the plain guard map the inline g/expr spelling produces —
  construction-validated at the def line. (waymark10.guards/defguard
  remains the CODE-guard macro; this one speaks sentences and trees.)

  A map after the law carries the way out and the per-field sentences:
  {:remedies […] :open \"…\" :errors {:meal_id \"not an on-list meal\"}}.
  :vars {:count '(count [c :kind where])} names what {count} reads,
  each evaluated as the law is; :hidden {:c \"…\"} is the sentence a
  {c.field} part reads as when binder c matched only rows the caller
  cannot see (left out, that sentence drops). `:pending :deny` makes an
  unknown verdict (a read no hook could answer, as on the render probe)
  refuse; the default, :allow, answers pending.

  A person's tap is one line, its law optional:

     (defguard principal-signs-off
       (hold \"A study is signed off only on The Principal's tap.\"))

  holds every agent's call on the door it sits on for the person's
  tap; with a law, '(< (data :amount_usd) 5000), it holds only while
  the law is false."
  [name clause & [law opts]]
  `(def ~name (sentence-guard ~(keyword name) ~clause ~law ~opts)))

;; ── sentence-first scenarios (waymark-442.2) ────────────────────────
;; The same shape as defguard, one turn outward: a guard's sentence
;; explains a refusal, a scenario's sentence IS the violation string
;; when the law stops keeping it. The def'd value is the plain map the
;; inline :scenarios spelling writes, construction-validated at the
;; def line — and since fingerprint-of is a whitelist that never names
;; :scenarios, adding one mints no law revision.

(defmacro defscenario
  "A declared policy test, sentence-first:

     (defscenario only-the-recipient-opens
       \"A letter addressed to someone else does not open for a
        curious sibling, and the refusal says which wall it hit.\"
       {:kind    :letter
        :attempt :open
        :row     {:state :waiting :data {:owner \"mom\" :to \"iris\"}}
        :as      {:id \"otto\" :type :person}
        :expect  {:refused :opener-is-recipient
                  :because \"Only the letter's recipient may open it.\"}})

  :cast and :teams (docs/spec-teams.md §8) declare the members and
  teams the law reads, with no store:

       :cast  {:ana {:roles [\"crew-lead\"]} :luis {} :parcelco {:type :outside}}
       :teams {:crew-b {:members [:ana :luis :parcelco]}}
       :as    :parcelco

  and `:as` naming a cast member acts as them.

  :expect names the GUARD, not the prose — a guard swapped for a
  different guard with the same words still fails. Drop the value into
  the declaration's :scenarios vector; waymark10.scenario decides,
  from the declaration alone, whether it is judged with no storage at
  all (`make check-…`) or through the HTTP door as the
  :core/law-scenarios conformance obligation."
  [name sentence smap]
  `(def ~name (scenario/scenario ~(keyword name) ~sentence ~smap)))
