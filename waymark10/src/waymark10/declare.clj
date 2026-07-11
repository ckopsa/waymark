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
  flag, quantity, prose, ref, measured-by) — plain functions returning
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
            [waymark10.resource :as r]
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
  so the fingerprint hashes the law, never the object."
  [name amap]
  (let [amap (if (and (map? amap)
                      (seq? (:handler amap))
                      (= 'fn (first (:handler amap))))
               (assoc amap :handler
                      `(with-meta ~(:handler amap)
                         {:waymark10/form '~(:handler amap)}))
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
    (when (= (contains? spec :expr) (contains? spec :count))
      (err (str "declare exactly one of :expr (an expression fact) or "
                ":count (an aggregate over a declared edge)")))
    (when (and (contains? spec :count) (seq (:over spec)))
      (err "a count's inputs are its declared edge; :over does not apply"))
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
  the engine maintains the target's label into."
  [kind & [{:keys [label]}]]
  (when-not (keyword? kind)
    (werr "ref names its target kind as a keyword: (ref :fund)"))
  (word [:waymark/ref]
        {:waymark10/props (cond-> {:kind kind}
                            label (assoc :label label))}))

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
  "The blocking sentence of a defguard: severity :refuse."
  [sentence]
  (when (or (not (string? sentence)) (str/blank? sentence))
    (werr "refuse takes the refusal sentence — a guard explains itself"))
  {:severity :refuse :explain sentence})

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

(defn sentence-guard
  "Build the plain expression-guard map a sentence + law pair means.
  Construction-validated here (the defaction pattern): the def line is
  where a malformed guard fails."
  [gname clause law]
  (when-not (and (map? clause) (#{:refuse :warning} (:severity clause))
                 (string? (:explain clause)))
    (throw (t/definition-error
            (str "defguard " (name gname)
                 ": the first argument is the sentence — (refuse \"…\") or (warn \"…\")"))))
  (when-not (seq? law)
    (throw (t/definition-error
            (str "defguard " (name gname)
                 ": a guard is a verdict — give it its law: "
                 "(defguard " (name gname) " (refuse \"…\") '(expr))"))))
  (let [w (rewrite-sentence-law law)
        inputs (:inputs (expr/info (expr/normalize w)))
        vars (into {}
                   (keep (fn [[_ p]]
                           (let [k (keyword p)]
                             (when-not (contains? inputs k)
                               [k (list 'data k)]))))
                   (re-seq sentence-placeholder (:explain clause)))]
    (g/expr (cond-> {:name gname
                     :when w
                     :explain (:explain clause)
                     :severity (:severity clause)}
              (seq vars) (assoc :vars vars)))))

(defmacro defguard
  "A sentence-first expression guard:

     (defguard fields-complete
       (refuse \"A transaction goes to review with its value type,
                amount, and effective date set.\")
       '(present? :value_type :amount :effective_date))

  Def's the plain guard map the inline g/expr spelling produces —
  construction-validated at the def line. (waymark10.guards/defguard
  remains the CODE-guard macro; this one speaks sentences and trees.)"
  [name clause & [law]]
  `(def ~name (sentence-guard ~(keyword name) ~clause ~law)))
