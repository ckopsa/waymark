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
  scope, and no reads but the scope's own :read hook, which `follow`
  asks for one row (and is unknown without). Ordering comparisons with a nil operand are false
  (a missing value satisfies no ordering claim); arithmetic over nil
  propagates nil. Numbers are longs and exact decimals — a float
  literal is refused at validation, not rounded.

  A scope may carry the `unknown` sentinel (a read no hook could
  answer, docs/spec-related-row-law.md §5). Every op propagates it,
  three-valued: `(and false unknown)` is false, `(and true unknown)`
  is unknown, `(or true unknown)` is true. No form can spell it.

  Two spellings that mean the same thing normalize to the same tree
  (see `normalize`), so reformatting a law never mints a revision.

  Scope is a plain map: {:vars {..} :data {..} :input {..} :now Instant
  :its (innermost-first seq of quantifier items) :read (fn [kind id])}."
  (:require [clojure.edn :as edn]
            [waymark10.money :as money])
  (:import (java.time Duration LocalDate LocalTime Instant OffsetDateTime Period ZoneId
                      ZoneOffset ZonedDateTime)
           (java.time.temporal ChronoUnit)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(set! *warn-on-reflection* true)

;; ── the vocabulary ──────────────────────────────────────────────────

(def ops
  "Every operator the law may speak. Growing this set is a design act:
  record the demanding declaration in docs/waymark10-design.md.

  count/sum (batch C): the waymark8 quantifier pair, earned by the
  blast-radius meter's own acceptance declarations (a flip count over
  a collection fact and a summed quantity are the shapes the meter's
  test laws speak) — recorded in docs/waymark10-c-notes.md.

  follow: one hop to a field of the row a ref names
  (docs/spec-related-row-law.md §2), earned by the household meal
  list's meal-is-listed.

  did/me/self (docs/spec-related-row-law.md §4): separation of duties —
  who took a door on this row, who acts now, and which row this is.
  They read the scope's :actors, :faces, :principal and :self, which
  the guard scope fills from its ctx; a scope without them answers
  unknown.

  exists/none/count-rows (docs/spec-related-row-law.md §3): other rows
  of a kind, asked through a where the guard's own scope fills, the
  row itself left out — earned by the landscaping booking's overlap.
  Authored as (exists [b :kind {field (op <expr>)}]); the binder is
  erased at normalization, and (count [b :kind …]) is count-rows.

  event: the value at one path in an outside event's body, or nil
  where it has none — (event \"data\" \"object\" \"id\") — earned by
  `:on-event`'s :match and :input (docs/spec-integrations.md § 3). It
  reads the scope's :event, and is unknown without one.

  str: its parts joined into one string, in order — earned by
  factory10's its-change-is-under-review, which finds a change by its
  stored born_from, (= (str \"ticket:\" (self))). An unknown part is
  unknown, a missing one is nil (docs/spec-related-row-law.md §7).

  clock/day/iso: a sentence's own form for an instant — the hour on
  the reader's clock, the day it falls on there, or the raw RFC 3339
  value — earned by span's neighbour-keeps-some-width, whose holes
  read instants (ticket 2af2d5dc). The guard scope lends its :zone.

  weeks/months/years/hours/minutes, days-between: the periods and the
  difference of docs/spec-calendar-time.md §2. (days n) (weeks n)
  (months n) (years n) are a Period, calendar arithmetic on a date;
  (hours n) (minutes n) are a Duration, clock arithmetic on an instant.
  (weeks n) normalizes to (days (* 7 n)), so the two spellings are one
  law. (days-between a b) is the whole days from date a to date b,
  negative when b is earlier.

  today/local-date/business-days/business-day?/next-business-day/
  working?: the calendar readers of docs/spec-calendar-time.md §2-§3.
  They read the scope's :calendar hook (the governing work_calendar),
  and are unknown without it, as an instant moved by a Period is: the
  same local time that many days later in that calendar's zone, never
  UTC by accident.

  money/amount/currency and / (docs/spec-ledger-money.md §2): the
  money literal (money \"18.00\" \"USD\"), its two readers, and the
  quotient — money ÷ a number is money, unrounded, to 12 places when it
  does not terminate. Money adds to and compares with money of its own
  currency only; of two currencies, or with a bare number, the answer
  is unknown, and the unknown says why (`unknown-why`). (round m) is
  m at its currency's minor unit in the house's rounding, the scope's
  :rounding (:half-even unless the house says :half-up)."
  '#{data input var now it get date
     = not= < <= > >=
     and or not
     + - * / min max abs
     money amount currency round
     str
     days weeks months years hours minutes days-between
     today local-date business-days business-day? next-business-day working?
     date-of is-set clock day iso
     every some count sum
     follow did me self event
     exists none count-rows})

(def ^:private quantifier-ops '#{every some count sum})

(deftype Unknown [why]
  Object
  (toString [_] "#waymark/unknown"))

(def unknown
  "The third verdict: what a read with no hook to answer it evaluates
  to. Neither true nor false, and never a refusal — a guard whose
  :when is unknown answers pending. Only a scope can carry it."
  (Unknown. nil))

(defn unknown? [v] (instance? Unknown v))

(defn unknown-why
  "The sentence an unknown carries when the law can say why it is not
  known — money of two currencies meeting — else nil."
  [v]
  (when (unknown? v) (.-why ^Unknown v)))

(defn- scalar-literal? [x]
  (or (nil? x) (boolean? x) (string? x)
      (int? x)          ; long-family integers; BigInt is refused
      (decimal? x)))

;; ── structural validation (the allowlist gate) ──────────────────────

(defn- parseable-date? [s]
  (and (string? s)
       (try (LocalDate/parse ^String s) true
            (catch Exception _ false))))

(defn- follow-ref?
  "The one ref a follow may start from: (data :f) or (input :f)."
  [x]
  (and (seq? x) (contains? '#{data input} (first x))
       (= 2 (count x)) (keyword? (second x))))

(defn- rows-binder?
  "[name :kind where]: the binder of exists, none and count over a kind."
  [x]
  (and (vector? x) (= 3 (count x)) (simple-symbol? (first x))
       (keyword? (second x)) (map? (nth x 2))))

(declare check-where)

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

          today
          (if (zero? n) problems (conj problems "(today) takes no arguments"))

          (me self)
          (if (zero? n) problems (conj problems (str "(" op ") takes no arguments")))

          did
          (if (and (pos? n) (every? keyword? args))
            problems
            (conj problems "(did :door …) takes one or more keyword door names"))

          it
          (cond
            (not (and (= n 1) (nat-int? (first args))))
            (conj problems "(it …) takes one non-negative index")
            (>= (long (first args)) (+ depth (count env)))
            (conj problems (str "(it " (first args) ") refers outside its quantifiers"))
            :else problems)

          event
          (if (and (pos? n) (every? string? args))
            problems
            (conj problems "(event \"a\" …) takes the path of one value in the event's body, as strings"))

          get
          (if (and (= n 2) (keyword? (second args)))
            (check (first args) env depth problems)
            (conj problems "(get <expr> :field) takes an expression and a keyword"))

          date
          (if (and (= n 1) (parseable-date? (first args)))
            problems
            (conj problems (str "(date …) takes one ISO date string; got "
                                (pr-str (vec args)))))

          (days weeks months years hours minutes date-of is-set clock day iso not abs
           local-date business-days business-day? next-business-day amount currency round)
          (if (= n 1)
            (check (first args) env depth problems)
            (conj problems (str "(" op " …) takes exactly one expression")))

          days-between
          (if (= n 2)
            (check (second args) env depth (check (first args) env depth problems))
            (conj problems "(days-between a b) takes two expressions"))

          working?
          (if (<= 2 n 3)
            (reduce (fn [p a] (check a env depth p)) problems args)
            (conj problems (str "(working? from to) takes a window's two instants or two dates, "
                                "and a calendar ref third when the resource names one")))

          ;; authored (follow <ref> :field); normalized at the def site
          ;; to (follow :kind <ref> :field)
          follow
          (let [[kind ref field] (if (= n 3) args (cons nil args))]
            (cond
              (not (<= 2 n 3))
              (conj problems "(follow <ref> :field) takes a ref and a field")
              (and (= n 3) (not (keyword? kind)))
              (conj problems "(follow :kind <ref> :field) names its kind as a keyword")
              (not (follow-ref? ref))
              (conj problems (str "(follow …) starts from (data :f) or (input :f) — "
                                  "one hop; got " (pr-str ref)))
              (not (keyword? field))
              (conj problems "(follow …) reads one keyword field of the row it reaches")
              :else problems))

          ;; authored (exists [b :kind {field (op <expr>)}]); normalized
          ;; to (exists :kind {…}), the binder erased
          (exists none count-rows)
          (cond
            (and (= n 1) (not= op 'count-rows) (rows-binder? (first args)))
            (check-where (nth (first args) 2) env depth problems)
            (and (= n 2) (keyword? (first args)) (map? (second args)))
            (check-where (second args) env depth problems)
            :else
            (conj problems (str "(" op " [binder :kind {field (op <expr>)}]) "
                                "takes one binder vector")))

          money
          (if (and (= n 2) (money/money (first args) (second args)))
            problems
            (conj problems (str "(money \"18.00\" \"USD\") takes an amount written as a "
                                "string and an ISO currency code; got " (pr-str (vec args)))))

          /
          (if (= n 2)
            (reduce (fn [p a] (check a env depth p)) problems args)
            (conj problems "(/ a b) divides exactly two expressions"))

          (= not= < <= > >=)
          (if (= n 2)
            (reduce (fn [p a] (check a env depth p)) problems args)
            (conj problems (str "(" op " …) compares exactly two expressions")))

          (+ * min max)
          (if (>= n 2)
            (reduce (fn [p a] (check a env depth p)) problems args)
            (conj problems (str "(" op " …) takes at least two expressions")))

          str
          (if (>= n 1)
            (reduce (fn [p a] (check a env depth p)) problems args)
            (conj problems "(str …) takes at least one expression"))

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
            ;; (count [b :kind where]) counts other rows (count-rows)
            (and (= op 'count) (= n 1) (rows-binder? (first args)))
            (check-where (nth (first args) 2) env depth problems)

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

(def ^:private where-ops
  "What a where condition may ask: the edge's join-ops, not=, and (in v …)."
  '#{= not= < <= > >= in})

(defn- check-where
  "A where map: each target field to (op <expr>) or (in <expr> …). The
  exprs read the guard's own scope; the binder is not in it, so these
  are filters, not joins."
  [where env depth problems]
  (reduce
   (fn [p [field c]]
     (let [[cop & xs] (when (seq? c) c)]
       (cond
         (not (keyword? field))
         (conj p (str "where field " (pr-str field) " is not a keyword"))
         (not (contains? where-ops cop))
         (conj p (str "where " field " takes (op <expr>), op one of "
                      (pr-str (sort-by str where-ops)) "; got " (pr-str c)))
         (and (not= 'in cop) (not= 1 (count xs)))
         (conj p (str "where " field " (" cop " …) compares one expression"))
         (and (= 'in cop) (empty? xs))
         (conj p (str "where " field " (in …) takes one or more expressions"))
         :else (reduce (fn [p x] (check x env depth p)) p xs))))
   problems
   (if (map? where) where {})))

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
        ;; (exists [b :kind where]): the binder is erased whole — the
        ;; where may not read it — and count over a kind is count-rows
        (and (contains? '#{exists none count} op) (rows-binder? (first args)))
        (let [[_ kind where] (first args)]
          (list (if (= op 'count) 'count-rows op) kind (debruijn where env depth)))

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

    (map? form)
    (into {} (map (fn [[k v]] [k (debruijn v env depth)])) form)

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
  '#{= not= < <= > >= and or not is-set every some did exists none
     business-day? working?})

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

        ;; a where's operands normalize and (in …) is a set; the
        ;; binder was erased in debruijn
        (exists none count-rows)
        (if (map? (second args))
          (list op (first args)
                (into {}
                      (map (fn [[f [cop & xs]]]
                             (let [xs (map norm xs)]
                               [f (cons cop (if (= 'in cop)
                                              (sort-by sort-key (distinct xs))
                                              xs))])))
                      (second args)))
          (cons op args))

        ;; a door named twice, or in another order, is the same question
        did
        (cons op (sort (distinct args)))

        ;; seven days and a week are one law (calendar spec §7)
        weeks
        (list 'days (norm (list '* 7 (first args))))

        ;; "18.00" and "18" are one amount, so one law
        money
        (if-some [a (money/canonical-amount (first args))]
          (list 'money a (second args))
          (cons op args))

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

(defn- kleene-and
  "False when any operand is known false; else unknown when any is
  unknown; else true. Stops at the first false."
  [vs]
  (reduce (fn [acc v]
            (cond (unknown? v) unknown
                  (truthy? v) acc
                  :else (reduced false)))
          true vs))

(defn- kleene-or
  "True when any operand is known true; else unknown when any is
  unknown; else false. Stops at the first true."
  [vs]
  (reduce (fn [acc v]
            (cond (unknown? v) unknown
                  (truthy? v) (reduced true)
                  :else acc))
          false vs))

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
    (and (money/money? a) (money/money? b) (= (:currency a) (:currency b)))
    (num-compare (:amount a) (:amount b))
    (and (instance? LocalDate a) (instance? LocalDate b))
    (.compareTo ^LocalDate a ^LocalDate b)
    (and (instance? Instant a) (instance? Instant b))
    (.compareTo ^Instant a ^Instant b)
    (and (string? a) (string? b)) (compare a b)
    :else nil))

(defn- val-eq [a b]
  (cond
    (and (number? a) (number? b)) (zero? (num-compare a b))
    ;; 18.00 dollars is 18 dollars: the amount compares, not its scale
    (and (money/money? a) (money/money? b))
    (and (= (:currency a) (:currency b))
         (zero? (num-compare (:amount a) (:amount b))))
    :else (= a b)))

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

;; ── money (docs/spec-ledger-money.md §2) ────────────────────────────

(defn- mixed-currency
  "Unknown, saying which two currencies, when money of two meets."
  [a b]
  (when-some [why (money/mixed-currency a b)]
    (Unknown. why)))

(defn- money-add
  "money ± money of its currency is money; of another it is unknown,
  naming both; money ± anything else is unknown — a bare number is not
  money."
  [a b sign]
  (if (and (money/money? a) (money/money? b))
    (or (mixed-currency a b)
        (let [x ^java.math.BigDecimal (:amount a)
              y ^java.math.BigDecimal (:amount b)]
          (money/->Money (if (neg? (long sign)) (.subtract x y) (.add x y))
                         (:currency a))))
    (Unknown. "only money adds to or subtracts from money: a bare number is not money")))

(defn- money-mul
  "money × a number is money, every digit kept."
  [m n]
  (money/->Money (.multiply ^java.math.BigDecimal (:amount m) (bigdec n))
                 (:currency m)))

(defn- div2
  "a ÷ b for a money or a number a and a number b: unrounded, to 12
  places when the quotient does not terminate. ÷ 0 is a missing value."
  [a b]
  (cond
    (not (number? b)) nil
    (zero? b) nil
    (money/money? a) (money/->Money (money/divide (:amount a) (bigdec b)) (:currency a))
    (number? a) (money/divide (bigdec a) (bigdec b))
    :else nil))

;; ── the house's calendar (docs/spec-calendar-time.md §2, §3) ────────

(defrecord BusinessDays [n])   ; (business-days n): a span only a calendar can walk

(def ^:private business-walk-cap
  "How many days a business-day walk may step before it answers nil."
  366)

(defn- calendar-in
  "The calendar `ref` names under the scope's :calendar hook (no ref:
  the default) — unknown when the scope has no hook or nothing governs."
  ([scope] (calendar-in scope nil))
  ([scope ref]
   (if-some [calendar-of (:calendar scope)]
     (if-some [c (calendar-of ref)] c unknown)
     unknown)))

(defn today-in
  "The date of `now` in the zone of the default calendar `calendar-of`
  answers: unknown with no hook or nothing that governs, nil with no
  clock. Derived facts bind :today with it."
  [now calendar-of]
  (let [c (calendar-in {:calendar calendar-of})]
    (cond (unknown? c) unknown
          (instance? Instant now) (.toLocalDate (.atZone ^Instant now ^ZoneId (:zone c)))
          :else nil)))

(defn- weekday
  "A date's weekday token, :mon to :sun."
  [^LocalDate d]
  (keyword (subs (.toLowerCase (.name (.getDayOfWeek d)) Locale/ROOT) 0 3)))

(defn- business-day
  "True when `d` is a workday of `cal` and none of its holidays."
  [cal ^LocalDate d]
  (and (contains? (:workdays cal) (weekday d))
       (not (contains? (:holidays cal) d))))

(defn- plus-business-days
  "`d` moved `n` business days of `cal`, one day at a time, skipping
  non-workdays and holidays; nil past the walk's cap."
  [cal ^LocalDate d n]
  (let [step (if (neg? (long n)) -1 1)]
    (loop [^LocalDate d d left (Math/abs (long n)) walked 0]
      (cond (zero? left) d
            (>= walked business-walk-cap) nil
            :else (let [d' (.plusDays d step)]
                    (recur d' (if (business-day cal d') (dec left) left) (inc walked)))))))

(defn- next-business-day
  "`d` itself when it is a business day of `cal`, else the next one;
  nil past the walk's cap."
  [cal ^LocalDate d]
  (loop [^LocalDate d d walked 0]
    (cond (business-day cal d) d
          (>= walked business-walk-cap) nil
          :else (recur (.plusDays d 1) (inc walked)))))

(defn- zoned-plus
  "An instant moved by a Period: the same local time that many calendar
  days later in the governing calendar's zone — unknown with no
  calendar, never UTC by accident."
  [scope ^Instant i ^Period p sign]
  (let [c (calendar-in scope)]
    (if (unknown? c)
      unknown
      (let [z (.atZone i ^ZoneId (:zone c))]
        (.toInstant ^ZonedDateTime (if (neg? (long sign)) (.minus z p) (.plus z p)))))))

(defn- business-walk
  "A date moved by (business-days n) on the governing calendar."
  [scope d b sign]
  (let [c (calendar-in scope)]
    (if (unknown? c) unknown (plus-business-days c d (* (long sign) (long (:n b)))))))

(defn- add2 [a b scope]
  (try
    (cond
      (or (unknown? a) (unknown? b)) unknown
      (or (nil? a) (nil? b)) nil
      (or (money/money? a) (money/money? b)) (money-add a b 1)
      (and (number? a) (number? b)) (num-add a b)
      (and (instance? LocalDate a) (instance? Period b)) (.plus ^LocalDate a ^Period b)
      (and (instance? Period a) (instance? LocalDate b)) (.plus ^LocalDate b ^Period a)
      (and (instance? Period a) (instance? Period b)) (.plus ^Period a ^Period b)
      (and (instance? Instant a) (instance? Duration b)) (.plus ^Instant a ^Duration b)
      (and (instance? Duration a) (instance? Instant b)) (.plus ^Instant b ^Duration a)
      (and (instance? Duration a) (instance? Duration b)) (.plus ^Duration a ^Duration b)
      ;; calendar days on an instant, and business days on a date,
      ;; ask the governing calendar (spec §2, §3)
      (and (instance? Instant a) (instance? Period b)) (zoned-plus scope a b 1)
      (and (instance? Period a) (instance? Instant b)) (zoned-plus scope b a 1)
      (and (instance? LocalDate a) (instance? BusinessDays b)) (business-walk scope a b 1)
      (and (instance? BusinessDays a) (instance? LocalDate b)) (business-walk scope b a 1)
      ;; a date has no hour
      :else nil)
    (catch Exception _ nil)))   ; calendar overflow is a missing value, not a crash

(defn- sub2 [a b scope]
  (try
    (cond
      (or (nil? a) (nil? b)) nil
      (or (money/money? a) (money/money? b)) (money-add a b -1)
      (and (number? a) (number? b)) (num-sub a b)
      (and (instance? LocalDate a) (instance? Period b)) (.minus ^LocalDate a ^Period b)
      (and (instance? Period a) (instance? Period b)) (.minus ^Period a ^Period b)
      (and (instance? Instant a) (instance? Duration b)) (.minus ^Instant a ^Duration b)
      (and (instance? Duration a) (instance? Duration b)) (.minus ^Duration a ^Duration b)
      (and (instance? Instant a) (instance? Period b)) (zoned-plus scope a b -1)
      (and (instance? LocalDate a) (instance? BusinessDays b)) (business-walk scope a b -1)
      :else nil)
    (catch Exception _ nil)))

(defn- mul2 [a b]
  (cond
    (and (number? a) (number? b)) (num-mul a b)
    (and (money/money? a) (number? b)) (money-mul a b)
    (and (number? a) (money/money? b)) (money-mul b a)
    :else nil))

(defn- whole-count
  "Numeric → that many whole units, a long in int range; nil for
  non-integral or out-of-range values — the calendar has no fractional
  day and the clock no fractional hour. The whole
  long family passes (int?, the same boundary scalar-literal? draws):
  JSON decoding hands scope values over as Integer, and evaluation is
  total over JSON-shaped values — (days (var :n)) must mean what
  (days n) means."
  [v]
  (try
    (cond
      (int? v)
      (when (<= Integer/MIN_VALUE (long v) Integer/MAX_VALUE)
        (long v))
      (decimal? v)
      ;; integral means scale ≤ 0 after the strip: stripTrailingZeros
      ;; turns 10M into 1E+1 (scale -1), and a negative scale is as
      ;; whole as a zero one — longValueExact restates it exactly
      (let [s (.stripTrailingZeros ^java.math.BigDecimal v)]
        (when-not (pos? (.scale s))
          (let [l (.longValueExact s)]
            (when (<= Integer/MIN_VALUE l Integer/MAX_VALUE)
              l))))
      :else nil)
    (catch ArithmeticException _ nil)))

(defn- span-of
  "(days n) (weeks n) (months n) (years n) → a Period; (hours n)
  (minutes n) → a Duration. nil where whole-count is nil, or where the
  span overflows (a week count past the int range)."
  [op v]
  (when-some [n (whole-count v)]
    (try
      (case op
        days    (Period/ofDays (int n))
        weeks   (Period/ofWeeks (int n))
        months  (Period/ofMonths (int n))
        years   (Period/ofYears (int n))
        hours   (Duration/ofHours n)
        minutes (Duration/ofMinutes n))
      (catch ArithmeticException _ nil))))

(defn- days-between
  "Whole days from date a to date b, negative when b is earlier; nil
  unless both are dates."
  [a b]
  (when (and (instance? LocalDate a) (instance? LocalDate b))
    (.between ChronoUnit/DAYS ^LocalDate a ^LocalDate b)))

(declare evaluate)

(defn- eval-quantifier [op coll-expr pred scope]
  (let [items (evaluate coll-expr scope)]
    (if (unknown? items)
      unknown
      (let [items (if (sequential? items) items ())
            pred-of (fn [item] (evaluate pred (update scope :its conj item)))]
        (case op
          every (kleene-and (map pred-of items))
          some  (kleene-or (map pred-of items)))))))

(defn- eval-count
  "(count <coll>) — the item count; with a predicate, the count of
  items satisfying it. A missing/non-sequential collection has no
  items: 0 (the quantifier empty-collection rule, numerically). An
  unknown collection, or an unknown predicate on any item, is an
  unknown count."
  [coll-expr pred scope]
  (let [items (evaluate coll-expr scope)]
    (cond
      (unknown? items) unknown
      (not (sequential? items)) 0
      (nil? pred) (long (clojure.core/count items))
      :else
      (let [vs (mapv #(evaluate pred (update scope :its conj %)) items)]
        (if (some unknown? vs)
          unknown
          (long (clojure.core/count (filter truthy? vs))))))))

(defn- eval-sum
  "(sum <coll> <expr>) — the exact sum of the body over the items.
  Empty/missing collections sum to 0; a non-numeric item value makes
  the whole sum nil (a missing addend is a missing sum — arithmetic's
  nil propagation, not a silent skip)."
  [coll-expr body scope]
  (let [items (evaluate coll-expr scope)]
    (if (unknown? items)
      unknown
      (let [vs (mapv #(evaluate body (update scope :its conj %))
                     (if (sequential? items) items ()))]
        (if (some unknown? vs)
          unknown
          (reduce (fn [acc v]
                    (if (number? v) (num-add acc v) (reduced nil)))
                  0 vs))))))

(defn where-conds
  "A canonical where under `scope`, as the condition list the scope's
  :rows-matching and :count-matching hooks take: [{:field :f :op :=
  :value v} | {:field :f :op :in :values [v …]} …], in field order.
  unknown when an operand is; nil when one is nil — a missing value
  matches no row."
  [where scope]
  (let [conds (mapv (fn [[field [cop & xs]]]
                      [field cop (mapv #(evaluate % scope) xs)])
                    (sort-by key where))
        vs (mapcat last conds)]
    (cond
      (some unknown? vs) unknown
      (some nil? vs) nil
      :else (mapv (fn [[field cop vs]]
                    (if (= 'in cop)
                      {:field field :op :in :values vs}
                      {:field field :op (keyword cop) :value (first vs)}))
                  conds))))

(defn- eval-rows
  "(exists :kind where), (none …) and (count-rows …) through the
  scope's :rows-matching and :count-matching hooks, the row itself
  (:self) left out, so moving a row never collides with its old self.
  No hook is unknown; a nil operand matches no row."
  [op kind where scope]
  (let [conds (where-conds where scope)
        self (:self scope)
        match (:rows-matching scope)]
    (cond
      (unknown? conds) unknown
      (nil? conds) (case op exists false none true count-rows 0)
      (nil? match) unknown
      (= op 'count-rows)
      (if-some [tally (:count-matching scope)]
        (let [n (long (or (tally kind conds) 0))
              mine? (and self (seq (match kind (conj conds {:field :id :op := :value self}) 1)))]
          (if mine? (max 0 (dec n)) n))
        unknown)
      :else
      (let [hit (boolean (some #(not= self (str %)) (match kind conds 2)))]
        (if (= op 'exists) hit (not hit))))))

(defn- str-part
  "One part of a (str …) as text, or nil for a value with no plain
  spelling (a map, a list): a keyword by its name, a decimal by its
  plain digits."
  [v]
  (cond
    (string? v) v
    (keyword? v) (name v)
    (decimal? v) (.toPlainString ^java.math.BigDecimal v)
    (or (boolean? v) (int? v) (instance? LocalDate v) (instance? Instant v)
        (instance? java.util.UUID v))
    (clojure.core/str v)
    :else nil))

(def ^:private rfc3339
  #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})")

(defn instant-of
  "An instant, or its RFC 3339 wire string (a :given row, a stored map
  not yet decoded), as an Instant; anything else nil."
  ^Instant [x]
  (cond (instance? Instant x) x
        (and (string? x) (re-matches rfc3339 x))
        (try (.toInstant (OffsetDateTime/parse ^String x)) (catch Exception _ nil))))

(def ^:private ^DateTimeFormatter hh-mm (DateTimeFormatter/ofPattern "HH:mm"))

(def ^:private ^DateTimeFormatter weekday-day-month
  (DateTimeFormatter/ofPattern "EEE d MMM" Locale/ENGLISH))

(defn- instant-text
  "(clock x), (day x) and (iso x) over an instant: the hour on the
  scope's :zone (UTC without one), the day it falls on there, or the
  raw RFC 3339 value. Anything else passes through."
  [op x zone]
  (if-some [i (instant-of x)]
    (let [z (.atZone i ^ZoneId (if (instance? ZoneId zone) zone ZoneOffset/UTC))]
      (case op
        clock (.format hh-mm z)
        day   (.format weekday-day-month z)
        iso   (str i)))
    x))

(defn- local-date-of
  "(local-date x): an instant's date in the governing calendar's zone;
  a date is its own date, anything else nil."
  [x scope]
  (cond (unknown? x) unknown
        (instance? LocalDate x) x
        :else (when-some [i (instant-of x)]
                (let [c (calendar-in scope)]
                  (if (unknown? c)
                    unknown
                    (.toLocalDate (.atZone i ^ZoneId (:zone c))))))))

(defn- working
  "True when the window [from, to) falls on business days of `cal` —
  inside its hours when it keeps them, so on one day — in its zone
  (spec §5). A window that ends before it starts is not working."
  [cal ^Instant from ^Instant to]
  (let [^ZoneId zone (:zone cal)
        a (.atZone from zone)
        b (.atZone to zone)]
    (cond
      (.isBefore to from) false

      (:hours cal)
      (let [{open :from close :to} (:hours cal)
            day (.toLocalDate a)]
        (and (= day (.toLocalDate b))
             (business-day cal day)
             (not (.isBefore (.toLocalTime a) ^LocalTime open))
             (not (.isAfter (.toLocalTime b) ^LocalTime close))))

      :else
      ;; no hours is the whole day: every day the window touches, the
      ;; day it ends at midnight on left out
      (let [first-day (.toLocalDate a)
            last-day (if (.isAfter to from)
                       (.toLocalDate (.atZone (.minusNanos to 1) zone))
                       first-day)
            span (.between ChronoUnit/DAYS first-day last-day)]
        (and (<= span business-walk-cap)
             (every? #(business-day cal (.plusDays first-day (long %)))
                     (range (inc span))))))))

(defn- working-days
  "True when every day of the whole-day window [from, to] is a business
  day of `cal` — a :grain :day window, so its hours do not enter (spec
  §5). A window that ends before it starts is not working."
  [cal ^LocalDate from ^LocalDate to]
  (let [span (.between ChronoUnit/DAYS from to)]
    (and (<= 0 span business-walk-cap)
         (every? #(business-day cal (.plusDays from (long %)))
                 (range (inc span))))))

(defn evaluate
  "Evaluate a canonical form under a scope map
  {:vars {} :data {} :input {} :now Instant :its (item …)}.
  Total: terminates on every well-formed form and never throws over
  JSON-shaped scope values; missing/mistyped values flow as nil
  (arithmetic) or false (ordering, quantifier predicates). An unknown
  operand makes the result unknown unless the known operands decide
  it (and/or and the quantifiers)."
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
        me    (if (contains? scope :principal) (:principal scope) unknown)
        self  (if (contains? scope :self) (:self scope) unknown)
        ;; a value in an outside event's body, by its path; no event
        ;; to read is unknown
        event (if (contains? scope :event)
                (reduce (fn [m k]
                          (when (map? m)
                            (let [kw (clojure.core/keyword k)]
                              (if (contains? m kw)
                                (clojure.core/get m kw)
                                (clojure.core/get m k)))))
                        (:event scope) args)
                unknown)

        ;; true when a face of the principal acting now is a face of
        ;; any logged actor of one of the doors; no log to ask is unknown
        did
        (if-some [actors (:actors scope)]
          (let [faces (set (:faces scope))]
            (boolean (some (fn [door] (some faces (actors door))) args)))
          unknown)

        get
        (let [v (evaluate (first args) scope)]
          (cond (unknown? v) unknown
                (map? v) (clojure.core/get v (second args))))

        money (money/money (first args) (second args))
        (amount currency)
        (let [v (evaluate (first args) scope)]
          (cond (unknown? v) v
                (money/money? v) (clojure.core/get v (keyword op))))
        ;; to the currency's minor unit, in the house's rounding
        round
        (let [v (evaluate (first args) scope)]
          (cond (unknown? v) v
                (money/money? v)
                (or (money/round v (:rounding scope))
                    (Unknown. (str (:currency v) " has no minor unit this engine knows, so it does not round")))))

        date
        (try (LocalDate/parse ^String (first args))
             (catch Exception _ nil))

        (days weeks months years hours minutes)
        (let [v (evaluate (first args) scope)]
          (if (unknown? v) unknown (span-of op v)))
        days-between
        (let [a (evaluate (first args) scope)
              b (evaluate (second args) scope)]
          (if (or (unknown? a) (unknown? b)) unknown (days-between a b)))

        ;; the house's day and its calendar (spec §2, §3), through the
        ;; scope's :calendar hook; unknown without one
        today (today-in (:now scope) (:calendar scope))
        local-date (local-date-of (evaluate (first args) scope) scope)
        business-days
        (let [v (evaluate (first args) scope)]
          (if (unknown? v) unknown (some-> (whole-count v) ->BusinessDays)))
        business-day?
        (let [v (evaluate (first args) scope)]
          (cond (unknown? v) unknown
                (not (instance? LocalDate v)) false
                :else (let [c (calendar-in scope)]
                        (if (unknown? c) unknown (business-day c v)))))
        next-business-day
        (let [v (evaluate (first args) scope)]
          (cond (unknown? v) unknown
                (not (instance? LocalDate v)) nil
                :else (let [c (calendar-in scope)]
                        (if (unknown? c) unknown (next-business-day c v)))))
        ;; (working? from to ref): a nil ref is the default calendar, a
        ;; ref that names no row is unknown; two dates are a day window
        working?
        (let [from (evaluate (first args) scope)
              to (evaluate (second args) scope)
              ref (when (= 3 (count args)) (evaluate (nth args 2) scope))]
          (cond
            (some unknown? [from to ref]) unknown
            (and (instance? LocalDate from) (instance? LocalDate to))
            (let [c (calendar-in scope ref)]
              (if (unknown? c) unknown (working-days c from to)))
            (not (and (instant-of from) (instant-of to))) false
            :else (let [c (calendar-in scope ref)]
                    (if (unknown? c) unknown (working c (instant-of from) (instant-of to))))))
        date-of (let [v (evaluate (first args) scope)]
                  (if (instance? Instant v)
                    (.toLocalDate (.atOffset ^Instant v ZoneOffset/UTC))
                    v))
        is-set  (let [v (evaluate (first args) scope)]
                  (if (unknown? v) unknown (some? v)))
        (clock day iso) (instant-text op (evaluate (first args) scope) (:zone scope))

        not (let [v (evaluate (first args) scope)]
              (if (unknown? v) unknown (clojure.core/not (truthy? v))))
        and (kleene-and (map #(evaluate % scope) args))
        or  (kleene-or (map #(evaluate % scope) args))

        (= not=)
        (let [a (evaluate (first args) scope)
              b (evaluate (second args) scope)]
          (cond
            (or (unknown? a) (unknown? b)) unknown
            (mixed-currency a b) (mixed-currency a b)
            (= op '=) (val-eq a b)
            :else (clojure.core/not (val-eq a b))))

        (< <=)
        (let [a (evaluate (first args) scope)
              b (evaluate (second args) scope)]
          (cond
            (or (unknown? a) (unknown? b)) unknown
            (mixed-currency a b) (mixed-currency a b)
            (or (nil? a) (nil? b)) false
            :else
            (if-some [c (val-compare a b)]
              (case op < (neg? (long c)) <= (clojure.core/not (pos? (long c))))
              false)))
        ;; > and >= never survive normalization, but evaluation stays
        ;; total for un-normalized authored forms
        (> >=)
        (let [a (evaluate (first args) scope)
              b (evaluate (second args) scope)]
          (cond
            (or (unknown? a) (unknown? b)) unknown
            (mixed-currency a b) (mixed-currency a b)
            (or (nil? a) (nil? b)) false
            :else
            (if-some [c (val-compare a b)]
              (case op > (pos? (long c)) >= (clojure.core/not (neg? (long c))))
              false)))

        (+ - * / min max abs)
        (let [all (mapv #(evaluate % scope) args)]
          (if-some [u (first (filter unknown? all))]
            u
            (case op
              + (reduce #(add2 %1 %2 scope) all)
              - (if (= 1 (count all))
                  (let [v (first all)]
                    (cond (number? v) (num-sub 0 v)
                          (money/money? v)
                          (money/->Money (.negate ^java.math.BigDecimal (:amount v))
                                         (:currency v))))
                  (sub2 (first all) (second all) scope))
              * (reduce mul2 all)
              / (div2 (first all) (second all))
              (min max)
              (let [vs (filterv some? all)]
                (when (and (seq vs) (every? number? vs))
            (reduce (fn [acc v]
                      (let [c (num-compare acc v)]
                        (case op
                          min (if (pos? c) v acc)
                          max (if (neg? c) v acc))))
                    vs)))
              abs
              (let [v (first all)]
                (cond
                  (decimal? v) (.abs ^java.math.BigDecimal v)
                  (number? v) (clojure.core/abs v)
                  :else nil)))))

        ;; unknown in is unknown out; a missing part is a missing string
        str
        (let [all (mapv #(evaluate % scope) args)]
          (cond
            (some unknown? all) unknown
            (some nil? all) nil
            :else (let [parts (mapv str-part all)]
                    (when (every? some? parts)
                      (apply clojure.core/str parts)))))

        ;; one hop through the scope's :read hook: a nil ref, an id no
        ;; row has and a row the caller may not see answer nil; no
        ;; hook, or no kind written in yet, is unknown
        follow
        (if (= 3 (count args))
          (let [[kind ref field] args
                id (evaluate ref scope)
                read (:read scope)]
            (cond
              (unknown? id) unknown
              (nil? id) nil
              (nil? read) unknown
              :else
              (when-some [row (read kind id)]
                (case field
                  :state (some-> (:state row) name)
                  :id    (some-> (:id row) str)
                  (clojure.core/get (:data row) field)))))
          unknown)

        ;; other rows of a kind, through the scope's hooks (§3); the
        ;; authored spelling erases its binder first
        (exists none count-rows)
        (if (vector? (first args))
          (evaluate (normalize form) scope)
          (eval-rows op (first args) (second args) scope))

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
  A form that asks who acts adds :doors (from did), :uses-me and
  :uses-self; a form that asks no such thing carries none of them.
  A form that follows adds :follows, the kinds written into its follows;
  one that asks exists/none/count-rows adds :queries, the kinds asked.
  A calendar reader adds :uses-calendar, and (today) :uses-today.
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
                  follow (reduce #(walk %2 %1)
                                 (cond-> acc
                                   (= 3 (count args)) (update :follows (fnil conj #{}) (first args)))
                                 (remove keyword? args))
                  did   (update acc :doors (fnil into #{}) args)
                  me    (assoc acc :uses-me true)
                  self  (assoc acc :uses-self true)
                  today (assoc acc :uses-today true :uses-calendar true)
                  (local-date business-days business-day? next-business-day working?)
                  (reduce #(walk %2 %1) (assoc acc :uses-calendar true) args)
                  (date it) acc
                  (exists none count-rows)
                  (if (vector? (first args))
                    (walk (normalize form) acc)
                    (reduce #(walk %2 %1)
                            (update acc :queries (fnil conj #{}) (first args))
                            (mapcat rest (vals (second args)))))
                  (every some count sum)
                  (if (rows-binder? (first args))
                    (walk (normalize form) acc)
                    (let [args (if (vector? (first args))
                                 [(second (first args)) (second args)]
                                 args)]
                      (reduce #(walk %2 %1) acc (remove nil? args))))
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
  (let [{:keys [vars data inputs uses-now uses-today doors uses-me uses-self queries]} (info form)
        undeclared (remove over vars)]
    (cond-> []
      (or (seq doors) uses-me uses-self)
      (conj "derived expressions read declared inputs, not the log or the actor: (did …), (me), (self)")
      (seq queries)
      (conj "derived expressions read declared inputs, not other rows: (exists …), (none …), (count-rows …)")
      (seq data)
      (conj (str "derived expressions read declared inputs, not (data …): "
                 (pr-str (sort data))))
      (seq inputs)
      (conj (str "derived expressions read declared inputs, not (input …): "
                 (pr-str (sort inputs))))
      uses-now
      (conj "derived expressions bind the clock as (var :now) via a declared clock input, not (now)")
      uses-today
      (conj "derived expressions bind the house's day as (var :today) via :today in :over, not (today)")
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

;; ── follow: the kind written in (docs/spec-related-row-law.md §2) ──

(defn follows
  "Every follow in the form, as [kind ref field]; kind is nil where
  none is written in yet."
  [form]
  (cond
    (seq? form)
    (let [[op & args] form
          inner (mapcat follows args)]
      (if (= 'follow op)
        (cons (if (= 3 (count args)) (vec args) (into [nil] args)) inner)
        inner))
    (vector? form) (mapcat follows form)
    (map? form) (mapcat follows (vals form))
    :else nil))

(defn bind-follows
  "Write the target kind into every (follow <ref> :field): `kind-of`
  answers the ref form's kind, or nil to leave that follow as written.
  The stored tree then evaluates under a later revision without the
  resident schema, and retargeting a ref moves the fingerprint."
  [form kind-of]
  (cond
    (seq? form)
    (let [[op & args] form]
      (if (and (= 'follow op) (= 2 (count args)))
        (if-some [kind (kind-of (first args))]
          (list 'follow kind (first args) (second args))
          form)
        (cons op (map #(bind-follows % kind-of) args))))
    (vector? form) (mapv #(bind-follows % kind-of) form)
    (map? form) (into {} (map (fn [[k v]] [k (bind-follows v kind-of)])) form)
    :else form))

;; ── exists / none / count-rows: the binder kept for evidence (§3) ───

(defn rows-binders
  "The binders of an authored form's exists/none/count over a kind, as
  name keyword → {:kind :where}, the where normalized. The canonical
  tree erases the names; a refusal binds its evidence under them."
  [form]
  (letfn [(walk [acc x]
            (cond
              (seq? x)
              (let [[op b] x
                    acc (if (and (contains? '#{exists none count} op) (rows-binder? b))
                          (assoc acc (keyword (first b))
                                 {:kind (second b)
                                  :where (nth (normalize (list 'exists b)) 2)})
                          acc)]
                (reduce walk acc (rest x)))
              (vector? x) (reduce walk acc x)
              (map? x) (reduce walk acc (vals x))
              :else acc))]
    (walk {} form)))

(defn rows-queries
  "Every exists/none/count-rows in a canonical form, as [kind where]."
  [form]
  (cond
    (seq? form)
    (let [[op & args] form
          inner (mapcat rows-queries args)]
      (if (contains? '#{exists none count-rows} op)
        (cons (vec args) inner)
        inner))
    (vector? form) (mapcat rows-queries form)
    (map? form) (mapcat rows-queries (vals form))
    :else nil))

;; ── first: the one correlated form, evidence-only (§3) ──────────────

(defn first-form?
  "(first [c :kind where] pred): a guard's :evidence may name one; a
  verdict may not — `first` is not in `ops`."
  [x]
  (and (seq? x) (= 'first (first x)) (= 3 (count x)) (rows-binder? (second x))))

(defn normalize-first
  "(first [c :kind where] pred) → {:kind :where :pred}, canonical: the
  where as exists normalizes it (it may not read c), the pred with c
  erased to (it 0), so the pred's own wheres may read the candidate —
  that is what makes it correlated. A canonical map passes through."
  [form]
  (if (map? form)
    form
    (let [[_ [c kind where] pred] form]
      {:kind kind
       :where (nth (normalize (list 'exists [c kind where])) 2)
       :pred (norm (debruijn pred {c 0} 1))})))

;; ── next-free: the next open window, evidence-only (calendar §5) ─────

(def ^:private next-free-cap
  "How many business days a next-free scan walks (calendar spec §5)."
  30)

(defn next-free
  "The refused window [from, to) and where it could go: {:zone :day
  :holiday} — the calendar's zone, the window's own day there and that
  day's holiday when it is one — with :from and :to the next window as
  long that `free?` admits, scanned over at most 30 business days of
  the calendar `ref` names (no ref: the default), from that day on. A
  business day's candidates start at its opening hour and step by the
  window's length inside its hours, or keep the window's time of day
  when the calendar keeps none; only one later than the window counts.
  nil when the scope cannot say (no calendar hook, no window). Asked
  only when a guard refuses, never in a verdict."
  [scope from-form to-form ref-form free?]
  (let [from (instant-of (evaluate from-form scope))
        to (instant-of (evaluate to-form scope))
        ref (when (some? ref-form) (evaluate ref-form scope))
        c (when (and from to (not (unknown? ref))) (calendar-in scope ref))]
    (when (and (some? c) (not (unknown? c)) (.isBefore ^Instant from ^Instant to))
      (let [^ZoneId zone (:zone c)
            len (Duration/between ^Instant from ^Instant to)
            start (.atZone ^Instant from zone)
            day (.toLocalDate start)
            starts (fn [^LocalDate d]
                     (if-some [{open :from close :to} (:hours c)]
                       (let [end (.atZone (.atTime d ^LocalTime close) zone)]
                         (take-while #(not (.isAfter (.plus ^ZonedDateTime % len) end))
                                     (iterate #(.plus ^ZonedDateTime % len)
                                              (.atZone (.atTime d ^LocalTime open) zone))))
                       [(.atZone (.atTime d (.toLocalTime start)) zone)]))
            found (loop [^LocalDate d day left next-free-cap walked 0]
                    (cond
                      (or (zero? left) (>= walked business-walk-cap)) nil
                      (not (business-day c d)) (recur (.plusDays d 1) left (inc walked))
                      :else (or (some (fn [^ZonedDateTime z]
                                        (let [s (.toInstant z)]
                                          (when (and (.isAfter s ^Instant from) (free? s (.plus s len))) s)))
                                      (starts d))
                                (recur (.plusDays d 1) (dec left) (inc walked)))))]
        (cond-> {:zone zone :day day :holiday (get (:holidays c) day)}
          found (assoc :from found :to (.plus ^Instant found len)))))))

(defn- date-of
  "A date, or its ISO wire string, as a LocalDate; anything else nil."
  ^LocalDate [x]
  (cond (instance? LocalDate x) x
        (string? x) (try (LocalDate/parse ^String x) (catch Exception _ nil))))

(defn next-free-days
  "next-free over a :grain :day window, whole days [from, to]: {:zone
  :day :holiday} as next-free answers them, with :from and :to the next
  window of as many days that `free?` admits, its first day a business
  day after `from`, scanned over at most 30 business days of the
  calendar `ref` names (no ref: the default). nil when the scope cannot
  say. Asked only when a guard refuses, never in a verdict."
  [scope from-form to-form ref-form free?]
  (let [from (date-of (evaluate from-form scope))
        to (date-of (evaluate to-form scope))
        ref (when (some? ref-form) (evaluate ref-form scope))
        c (when (and from to (not (unknown? ref))) (calendar-in scope ref))]
    (when (and (some? c) (not (unknown? c)) (not (.isBefore ^LocalDate to ^LocalDate from)))
      (let [span (- (.toEpochDay ^LocalDate to) (.toEpochDay ^LocalDate from))
            found (loop [^LocalDate d (.plusDays ^LocalDate from 1) left next-free-cap walked 0]
                    (cond
                      (or (zero? left) (>= walked business-walk-cap)) nil
                      (not (business-day c d)) (recur (.plusDays d 1) left (inc walked))
                      (free? d (.plusDays d span)) d
                      :else (recur (.plusDays d 1) (dec left) (inc walked))))]
        (cond-> {:zone (:zone c) :day from :holiday (get (:holidays c) from)}
          found (assoc :from found :to (.plusDays ^LocalDate found span)))))))
