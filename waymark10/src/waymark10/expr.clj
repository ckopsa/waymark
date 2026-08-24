(ns waymark10.expr
  "The law's expression language: a closed, total vocabulary of EDN forms.

  A derivation or a pure guard verdict is a form like

      (+ (var :start_date) (days (- (* 7 (var :weeks)) 1)))
      (every [d (var :days)] (or (is-set (get d :meal_id))
                                 (= (get d :eating_out) true)))

  The tree is the law: forms serialize losslessly (waymark10.wire),
  live in fingerprints as trees, diff path-by-path, and evaluate under
  any stored revision. Arbitrary Clojure is refused at the boundary —
  the vocabulary is a closed allowlist, grown one node at a time when
  a ported declaration demands it, never speculatively.

  Evaluation is total: no calls, no recursion, no names beyond the
  scope, no reads. Ordering comparisons with a nil operand are false
  (a missing value satisfies no ordering claim); arithmetic over nil
  propagates nil. Numbers are longs and exact decimals — a float
  literal is refused at validation, not rounded.

  Two spellings that mean the same thing normalize to the same tree
  (see `normalize`), so reformatting a law never mints a revision.

  Scope is a plain map: {:vars {..} :data {..} :input {..} :now Instant
  :its (innermost-first seq of quantifier items)}."
  (:require [clojure.edn :as edn])
  (:import (java.time LocalDate Instant Period ZoneOffset)))

(set! *warn-on-reflection* true)

;; ── the vocabulary ──────────────────────────────────────────────────

(def ops
  "Every operator the law may speak. Growing this set is a design act:
  record the demanding declaration in docs/waymark10-design.md.

  count/sum (batch C): the waymark8 quantifier pair, earned by the
  blast-radius meter's own acceptance declarations (a flip count over
  a collection fact and a summed quantity are the shapes the meter's
  test laws speak) — recorded in docs/waymark10-c-notes.md."
  '#{data input var now it get date
     = not= < <= > >=
     and or not
     + - * min max abs
     days date-of is-set
     every some count sum})

(def ^:private quantifier-ops '#{every some count sum})

(defn- scalar-literal? [x]
  (or (nil? x) (boolean? x) (string? x)
      (int? x)          ; long-family integers; BigInt is refused
      (decimal? x)))

;; ── structural validation (the allowlist gate) ──────────────────────

(defn- parseable-date? [s]
  (and (string? s)
       (try (LocalDate/parse ^String s) true
            (catch Exception _ false))))

(defn- check
  "Accumulate problem strings for `form`. `env` is the set of bound
  binder symbols (authored spelling); `depth` counts enclosing
  quantifiers (canonical `(it n)` spelling)."
  [form env depth problems]
  (cond
    (scalar-literal? form) problems

    (double? form)
    (conj problems (str "float literal " form " — write an exact decimal ("
                        form "M); floats are not in the law's vocabulary"))

    (symbol? form)
    (if (contains? env form)
      problems
      (conj problems (str "unbound symbol " form
                          " — only quantifier binders may appear bare")))

    (keyword? form)
    (conj problems (str "keyword " form " outside a reference form"))

    (seq? form)
    (let [[op & args] form
          n (count args)]
      (if-not (contains? ops op)
        (conj problems (str "unknown form (" op " …) — not in the law's vocabulary"))
        (case op
          (data input var)
          (if (and (= n 1) (keyword? (first args)))
            problems
            (conj problems (str "(" op " …) takes exactly one keyword field name")))

          now
          (if (zero? n) problems (conj problems "(now) takes no arguments"))

          it
          (cond
            (not (and (= n 1) (nat-int? (first args))))
            (conj problems "(it …) takes one non-negative index")
            (>= (long (first args)) (+ depth (count env)))
            (conj problems (str "(it " (first args) ") refers outside its quantifiers"))
            :else problems)

          get
          (if (and (= n 2) (keyword? (second args)))
            (check (first args) env depth problems)
            (conj problems "(get <expr> :field) takes an expression and a keyword"))

          date
          (if (and (= n 1) (parseable-date? (first args)))
            problems
            (conj problems (str "(date …) takes one ISO date string; got "
                                (pr-str (vec args)))))

          (days date-of is-set not abs)
          (if (= n 1)
            (check (first args) env depth problems)
            (conj problems (str "(" op " …) takes exactly one expression")))

          (= not= < <= > >=)
          (if (= n 2)
            (reduce (fn [p a] (check a env depth p)) problems args)
            (conj problems (str "(" op " …) compares exactly two expressions")))

          (+ * min max)
          (if (>= n 2)
            (reduce (fn [p a] (check a env depth p)) problems args)
            (conj problems (str "(" op " …) takes at least two expressions")))

          ;; ≥1: normalization may dedup (and x x) down to (and x) when
          ;; x is not boolean-valued — the wrapper keeps the coercion
          (and or)
          (if (>= n 1)
            (reduce (fn [p a] (check a env depth p)) problems args)
            (conj problems (str "(" op " …) takes at least one expression")))

          -
          (if (<= 1 n 2)
            (reduce (fn [p a] (check a env depth p)) problems args)
            (conj problems "(- …) takes one or two expressions"))

          (every some count sum)
          (cond
            ;; (count <coll>) alone counts the items — the one 1-ary
            ;; quantifier spelling (batch C)
            (and (= op 'count) (= n 1))
            (check (first args) env depth problems)

            ;; authored spelling: (every [d <coll>] <pred>)
            (and (= n 2) (vector? (first args)))
            (let [[binder coll] (first args)]
              (if (and (simple-symbol? binder) (= 2 (count (first args))))
                (-> problems
                    (as-> p (check coll env depth p))
                    (as-> p (check (second args) (conj env binder) depth p)))
                (conj problems (str "(" op " [binder <coll>] <body>) needs a "
                                    "symbol binder and a collection expression"))))
            ;; canonical spelling: (every <coll> <pred>) with (it n) refs
            (= n 2)
            (-> problems
                (as-> p (check (first args) env depth p))
                (as-> p (check (second args) env (inc depth) p)))
            :else
            (conj problems (str "(" op " …) takes a binder vector (or collection) "
                                "and a " (if (= op 'sum) "body expression" "predicate")
                                (when (= op 'count)
                                  "; (count <coll>) alone counts the items")))))))

    :else
    (conj problems (str "value " (pr-str form) " ("
                        (some-> form class .getSimpleName)
                        ") is not in the law's vocabulary"))))

(defn problems
  "Vector of problem strings for a form; empty when well-formed.
  Accepts both authored (named binders) and canonical (de Bruijn)
  spellings."
  [form]
  (check form #{} 0 []))

(defn well-formed? [form]
  (empty? (problems form)))

;; ── reading (the boundary) ──────────────────────────────────────────

(def ^:private max-form-chars 16384)

(defn- refuse-tag [tag _]
  (throw (ex-info (str "tagged literal #" tag " is not in the law's vocabulary")
                  {:waymark10/refused tag})))

(defn read-form
  "Read one law form from an EDN string. Refuses oversized input,
  every tagged literal, and any form outside the vocabulary. Returns
  the form; throws ex-info with :problems otherwise."
  [^String s]
  (when (> (.length s) max-form-chars)
    (throw (ex-info (str "law form exceeds " max-form-chars " characters")
                    {:problems ["form too large"]})))
  (let [form (edn/read-string {:readers {'inst (partial refuse-tag 'inst)
                                         'uuid (partial refuse-tag 'uuid)}
                               :default refuse-tag}
                              s)
        ps (problems form)]
    (when (seq ps)
      (throw (ex-info (str "law form refused: " (first ps)) {:problems ps})))
    form))

;; ── normalization ───────────────────────────────────────────────────
;; Two spellings that mean the same thing become the same tree, so a
;; reformat never revises the law and a real change diffs at the exact
;; leaf that moved. Idempotent by construction (property-tested).

(defn- debruijn
  "Erase authored binder names: (every [d coll] …d…) becomes
  (every coll …(it k)…) with k counting intervening quantifiers,
  innermost = 0. Canonical forms pass through unchanged."
  [form env depth]
  (cond
    (symbol? form)
    (if-some [bound-at (clojure.core/get env form)]
      (list 'it (- depth (long bound-at) 1))
      form)

    (seq? form)
    (let [[op & args] form]
      (cond
        (and (contains? quantifier-ops op) (vector? (first args)))
        (let [[binder coll] (first args)
              pred (second args)]
          (list op
                (debruijn coll env depth)
                (debruijn pred (assoc env binder depth) (inc depth))))

        (contains? quantifier-ops op)
        (if (= 1 (clojure.core/count args))
          ;; (count <coll>) — no binder, no depth
          (list op (debruijn (first args) env depth))
          (list op
                (debruijn (first args) env depth)
                (debruijn (second args) env (inc depth))))

        :else
        (cons op (map #(debruijn % env depth) args))))

    :else form))

(defn- norm-decimal ^java.math.BigDecimal [^java.math.BigDecimal d]
  (let [s (.stripTrailingZeros d)]
    (if (neg? (.scale s)) (.setScale s 0) s)))

(defn- sort-key
  "Total order over canonical forms for commutative-operand sorting."
  [form]
  (pr-str form))

(def ^:private boolean-valued-ops
  "Ops whose value is always a boolean — the only operands and/or/not
  may collapse around without changing the value domain: (and x x)
  means (boolean x), which is x itself only when x is already boolean."
  '#{= not= < <= > >= and or not is-set every some})

(defn- boolean-valued? [form]
  (and (seq? form) (contains? boolean-valued-ops (first form))))

(defn- norm
  "Bottom-up local rewrites. Children are already normalized."
  [form]
  (cond
    (decimal? form) (norm-decimal form)

    (seq? form)
    (let [[op & args] form
          args (mapv norm args)]
      (case op
        >  (list '< (second args) (first args))
        >= (list '<= (second args) (first args))

        (and or)
        (let [flat (into []
                         (mapcat (fn [a]
                                   (if (and (seq? a) (= op (first a)))
                                     (rest a)
                                     [a])))
                         args)
              deduped (into [] (distinct) flat)]
          (if (and (= 1 (count deduped)) (boolean-valued? (first deduped)))
            (first deduped)
            (cons op deduped)))

        not
        (let [a (first args)]
          (cond
            (and (seq? a) (= 'not (first a))
                 (boolean-valued? (second a)))  (second a)
            (and (seq? a) (= '= (first a)))    (cons 'not= (rest a))
            (and (seq? a) (= 'not= (first a))) (cons '= (rest a))
            :else (list 'not a)))

        (= not= min max + *)
        (cons op (sort-by sort-key args))

        ;; every/some binder erasure happened in debruijn; other ops
        ;; carry their (normalized) arguments unchanged
        (cons op args)))

    :else form))

(defn normalize
  "Authored or canonical form → canonical form. Idempotent."
  [form]
  (norm (debruijn form {} 0)))

;; ── canonical gensyms (waymark-j82) ─────────────────────────────────
;; The same discipline `normalize` keeps for the expression language,
;; one turn outward: a stored CLOJURE form must print the same today
;; and next boot, or the fingerprint it feeds is not an identity.
;;
;; A defguard/defhandler body is captured AFTER READ, so `#(= :active
;; (:state %))` is already `(fn* [p1__10794#] …)` — and that counter
;; is global to the load. Anything compiled earlier moves it, so the
;; guard's hash drifted whenever an unrelated namespace grew a form.
;; Renaming every minted symbol to its position of first appearance
;; makes the printed form a function of the code and nothing else.

(def ^:private minted-symbol
  ;; the three shapes the reader and the macroexpander mint:
  ;; #()'s p1__1234#, syntax-quote's x__1234__auto__, gensym's G__1234
  #"^(?:.+__\d+#|.+__\d+__auto__|G__\d+)$")

(defn minted-symbol?
  "True for a symbol the reader or a macro minted rather than an
  author wrote — the ones whose names carry a load-global counter."
  [x]
  (and (symbol? x)
       (nil? (namespace x))
       (some? (re-matches minted-symbol (name x)))))

(defn canonical-gensyms
  "Rename every minted symbol in `form` to g__1, g__2 … in order of
  first appearance. A form that carries none comes back identical
  (=, and printing byte-for-byte), so canonicalizing costs the
  gensym-free world exactly nothing — the pinned literal hashes hold.
  Idempotent: g__1 is not itself a minted shape.

  The trade, recorded: an author who literally writes `g__1` beside a
  reader gensym has them conflated. Nobody writes that symbol on
  purpose, and the cost would be a hash collision inside one guard
  body, not a wrong verdict."
  [form]
  (let [seen (volatile! {})
        rename (fn [s]
                 (or (get @seen s)
                     (let [c (symbol (str "g__" (inc (count @seen))))]
                       (vswap! seen assoc s c)
                       c)))
        walk (fn walk [x]
               (cond
                 (minted-symbol? x) (rename x)
                 (record? x) x
                 (map? x) (into (empty x)
                                (map (fn [[k v]] [(walk k) (walk v)]))
                                x)
                 (vector? x) (into (with-meta [] (meta x)) (map walk) x)
                 (set? x) (into (empty x) (map walk) x)
                 (seq? x) (with-meta (apply list (mapv walk x)) (meta x))
                 :else x))]
    (walk form)))

;; ── evaluation (total) ──────────────────────────────────────────────

(defn- truthy? [v] (boolean v))

(defn- num-compare
  "Exact comparison across long/decimal. Returns an int."
  ^long [a b]
  (if (or (decimal? a) (decimal? b))
    (.compareTo (bigdec a) (bigdec b))
    (compare a b)))

(defn- val-compare
  "Ordering across comparable value pairs; nil when incomparable —
  an incomparable pair satisfies no ordering claim."
  [a b]
  (cond
    (and (number? a) (number? b)) (num-compare a b)
    (and (instance? LocalDate a) (instance? LocalDate b))
    (.compareTo ^LocalDate a ^LocalDate b)
    (and (instance? Instant a) (instance? Instant b))
    (.compareTo ^Instant a ^Instant b)
    (and (string? a) (string? b)) (compare a b)
    :else nil))

(defn- val-eq [a b]
  (if (and (number? a) (number? b))
    (zero? (num-compare a b))
    (= a b)))

(defn- num-add [a b]
  (if (or (decimal? a) (decimal? b))
    (.add (bigdec a) (bigdec b))
    (+' a b)))

(defn- num-sub [a b]
  (if (or (decimal? a) (decimal? b))
    (.subtract (bigdec a) (bigdec b))
    (-' a b)))

(defn- num-mul [a b]
  (if (or (decimal? a) (decimal? b))
    (.multiply (bigdec a) (bigdec b))
    (*' a b)))

(defn- add2 [a b]
  (try
    (cond
      (or (nil? a) (nil? b)) nil
      (and (number? a) (number? b)) (num-add a b)
      (and (instance? LocalDate a) (instance? Period b)) (.plus ^LocalDate a ^Period b)
      (and (instance? Period a) (instance? LocalDate b)) (.plus ^LocalDate b ^Period a)
      (and (instance? Period a) (instance? Period b)) (.plus ^Period a ^Period b)
      :else nil)
    (catch Exception _ nil)))   ; calendar overflow is a missing value, not a crash

(defn- sub2 [a b]
  (try
    (cond
      (or (nil? a) (nil? b)) nil
      (and (number? a) (number? b)) (num-sub a b)
      (and (instance? LocalDate a) (instance? Period b)) (.minus ^LocalDate a ^Period b)
      :else nil)
    (catch Exception _ nil)))

(defn- mul2 [a b]
  (if (and (number? a) (number? b)) (num-mul a b) nil))

(defn- whole-days
  "Numeric → Period of that many days; nil for non-integral or
  out-of-range values — the calendar has no fractional day. The whole
  long family passes (int?, the same boundary scalar-literal? draws):
  JSON decoding hands scope values over as Integer, and evaluation is
  total over JSON-shaped values — (days (var :n)) must mean what
  (days n) means."
  [v]
  (try
    (cond
      (int? v)
      (when (<= Integer/MIN_VALUE (long v) Integer/MAX_VALUE)
        (Period/ofDays (int (long v))))
      (decimal? v)
      ;; integral means scale ≤ 0 after the strip: stripTrailingZeros
      ;; turns 10M into 1E+1 (scale -1), and a negative scale is as
      ;; whole as a zero one — longValueExact restates it exactly
      (let [s (.stripTrailingZeros ^java.math.BigDecimal v)]
        (when-not (pos? (.scale s))
          (let [l (.longValueExact s)]
            (when (<= Integer/MIN_VALUE l Integer/MAX_VALUE)
              (Period/ofDays (int l))))))
      :else nil)
    (catch ArithmeticException _ nil)))

(declare evaluate)

(defn- eval-quantifier [op coll-expr pred scope]
  (let [items (evaluate coll-expr scope)
        items (if (sequential? items) items ())
        pred-of (fn [item]
                  (truthy? (evaluate pred (update scope :its conj item))))]
    (case op
      every (boolean (every? pred-of items))
      some  (boolean (some pred-of items)))))

(defn- eval-count
  "(count <coll>) — the item count; with a predicate, the count of
  items satisfying it. A missing/non-sequential collection has no
  items: 0 (the quantifier empty-collection rule, numerically)."
  ^long [coll-expr pred scope]
  (let [items (evaluate coll-expr scope)
        items (if (sequential? items) items ())]
    (if (nil? pred)
      (long (clojure.core/count items))
      (long (clojure.core/count
             (filter #(truthy? (evaluate pred (update scope :its conj %)))
                     items))))))

(defn- eval-sum
  "(sum <coll> <expr>) — the exact sum of the body over the items.
  Empty/missing collections sum to 0; a non-numeric item value makes
  the whole sum nil (a missing addend is a missing sum — arithmetic's
  nil propagation, not a silent skip)."
  [coll-expr body scope]
  (let [items (evaluate coll-expr scope)
        items (if (sequential? items) items ())]
    (reduce (fn [acc item]
              (let [v (evaluate body (update scope :its conj item))]
                (if (number? v) (num-add acc v) (reduced nil))))
            0 items)))

(defn evaluate
  "Evaluate a canonical form under a scope map
  {:vars {} :data {} :input {} :now Instant :its (item …)}.
  Total: terminates on every well-formed form and never throws over
  JSON-shaped scope values; missing/mistyped values flow as nil
  (arithmetic) or false (ordering, quantifier predicates)."
  [form scope]
  (cond
    (scalar-literal? form) form

    (seq? form)
    (let [[_ & args] form
          op (first form)]
      (case op
        data  (clojure.core/get (:data scope) (first args))
        input (clojure.core/get (:input scope) (first args))
        var   (clojure.core/get (:vars scope) (first args))
        now   (:now scope)
        it    (nth (:its scope) (long (first args)) nil)

        get
        (let [v (evaluate (first args) scope)]
          (when (map? v) (clojure.core/get v (second args))))

        date
        (try (LocalDate/parse ^String (first args))
             (catch Exception _ nil))

        days    (whole-days (evaluate (first args) scope))
        date-of (let [v (evaluate (first args) scope)]
                  (if (instance? Instant v)
                    (.toLocalDate (.atOffset ^Instant v ZoneOffset/UTC))
                    v))
        is-set  (some? (evaluate (first args) scope))

        not (clojure.core/not (truthy? (evaluate (first args) scope)))
        and (boolean (every? #(truthy? (evaluate % scope)) args))
        or  (boolean (some #(truthy? (evaluate % scope)) args))

        =    (val-eq (evaluate (first args) scope) (evaluate (second args) scope))
        not= (clojure.core/not
              (val-eq (evaluate (first args) scope) (evaluate (second args) scope)))

        (< <=)
        (let [a (evaluate (first args) scope)
              b (evaluate (second args) scope)]
          (if (or (nil? a) (nil? b))
            false
            (if-some [c (val-compare a b)]
              (case op < (neg? (long c)) <= (clojure.core/not (pos? (long c))))
              false)))
        ;; > and >= never survive normalization, but evaluation stays
        ;; total for un-normalized authored forms
        (> >=)
        (let [a (evaluate (first args) scope)
              b (evaluate (second args) scope)]
          (if (or (nil? a) (nil? b))
            false
            (if-some [c (val-compare a b)]
              (case op > (pos? (long c)) >= (clojure.core/not (neg? (long c))))
              false)))

        + (reduce add2 (map #(evaluate % scope) args))
        - (if (= 1 (count args))
            (let [v (evaluate (first args) scope)]
              (when (number? v) (num-sub 0 v)))
            (sub2 (evaluate (first args) scope) (evaluate (second args) scope)))
        * (reduce mul2 (map #(evaluate % scope) args))

        (min max)
        (let [vs (into [] (comp (map #(evaluate % scope)) (filter some?)) args)]
          (when (and (seq vs) (every? number? vs))
            (reduce (fn [acc v]
                      (let [c (num-compare acc v)]
                        (case op
                          min (if (pos? c) v acc)
                          max (if (neg? c) v acc))))
                    vs)))

        abs
        (let [v (evaluate (first args) scope)]
          (cond
            (decimal? v) (.abs ^java.math.BigDecimal v)
            (number? v) (clojure.core/abs v)
            :else nil))

        (every some count sum)
        (if (vector? (first args))
          ;; authored spelling — erase the binder names, then evaluate
          (evaluate (normalize form) scope)
          (case op
            (every some) (eval-quantifier op (first args) (second args) scope)
            count (eval-count (first args) (second args) scope)
            sum (eval-sum (first args) (second args) scope)))))

    ;; symbols/keywords outside the vocabulary reach here only on
    ;; un-validated input; missing means nil
    :else nil))

;; ── scope analysis ──────────────────────────────────────────────────

(defn info
  "What the form reads: {:vars #{} :data #{} :inputs #{} :uses-now bool}.
  Judges and reads are derived from the tree, never sniffed."
  [form]
  (letfn [(walk [form acc]
            (if (seq? form)
              (let [[op & args] form]
                (case op
                  var   (update acc :vars conj (first args))
                  data  (update acc :data conj (first args))
                  input (update acc :inputs conj (first args))
                  now   (assoc acc :uses-now true)
                  (date it) acc
                  (every some count sum)
                  (let [args (if (vector? (first args))
                               [(second (first args)) (second args)]
                               args)]
                    (reduce #(walk %2 %1) acc (remove nil? args)))
                  (reduce #(walk %2 %1)
                          acc
                          (remove keyword? args))))
              acc))]
    (walk form {:vars #{} :data #{} :inputs #{} :uses-now false})))

(defn derived-scope-problems
  "A derivation reads only its declared inputs: no data/input/now
  references; every (var …) must name an entry of `over` (a set of
  keywords; a clock derivation declares :now there)."
  [form over]
  (let [{:keys [vars data inputs uses-now]} (info form)
        undeclared (remove over vars)]
    (cond-> []
      (seq data)
      (conj (str "derived expressions read declared inputs, not (data …): "
                 (pr-str (sort data))))
      (seq inputs)
      (conj (str "derived expressions read declared inputs, not (input …): "
                 (pr-str (sort inputs))))
      uses-now
      (conj "derived expressions bind the clock as (var :now) via a declared clock input, not (now)")
      (seq undeclared)
      (conj (str "expression reads inputs the declaration never named: "
                 (pr-str (sort undeclared)))))))

(defn guard-scope-problems
  "A guard verdict is a function of the row, the input, and the clock:
  (data …), (input …), (now) — never bare (var …)."
  [form]
  (let [{:keys [vars]} (info form)]
    (cond-> []
      (seq vars)
      (conj (str "guard expressions read (data …)/(input …)/(now), not (var …): "
                 (pr-str (sort vars)))))))
