(ns waymark10.measures
  "Named measures (docs/spec-measures.md §2, §8; build step 1): a kind's
  `:measures` facet — {name {:label :of :stat :among :where :field :at
  :over :tell :at-least}} — the vocabulary its keys speak, the
  [measure-shape] judgment of one measure, and the token `<kind>.<name>`
  a goal, a reading and a slot name it by. The measure is law; nothing
  sweeps yet.

  The slot stats and the :field and :at spellings are the dashboard
  slot's own (waymark10.dashboard reads them from here), so a measure
  and a slot say a statistic the same way. Below waymark10.resource on
  purpose: the checks, the fingerprint and normalization all read it."
  (:require [clojure.string :as str]
            [waymark10.expr :as expr])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def slot-stats
  "The statistics a dashboard slot's measure panel may ask for."
  ["count" "sum" "median" "p90"])

(def stats
  "The statistics a declared measure may name: :rate, a measure's own,
  and the slot's four."
  (into ["rate"] slot-stats))

(def ^:private field-stats #{"sum" "median" "p90"})

(def measure-keys
  "One measure's whole authored surface. :default-target is goal step
  2's boot seed, admitted here so declaring it is not a typo."
  #{:label :of :stat :among :where :field :at :over :tell :at-least
    :default-target})

(def default-at-least
  "The fewest :among rows a window needs before it is judged."
  10)

(def goal-ops
  "The comparisons a goal holds its measure to."
  [">=" "<="])

(defn default-target?
  "Is `t` a well-formed :default-target — {:op \">=\" :target 0.95M},
  both keys and no other, the op one of goal-ops and the target a
  number?"
  [t]
  (boolean
   (and (map? t)
        (= #{:op :target} (set (keys t)))
        (some #{(:op t)} goal-ops)
        (number? (:target t)))))

(defn spelled
  "A keyword or a string as its text; nil for anything else."
  [v]
  (cond (keyword? v) (name v)
        (string? v) v))

(def transition-prefix
  "A measure's :at spelling that counts the history log instead of a
  time field: transition:<action> (dashboard measures 2/3)."
  "transition:")

(defn transition-action
  "The action a measure's :at names as transition:<action> — the text
  after the prefix, blank when none is given — or nil when :at names a
  time field."
  [at]
  (let [s (str at)]
    (when (str/starts-with? s transition-prefix)
      (str/trim (subs s (count transition-prefix))))))

(defn parse-field
  "A measure's :field as what it reads off each row: {:field k} for a
  number field, {:duration [from to]} for duration(<from>,<to>) — the
  seconds between two time fields. nil when blank or unparseable."
  [s]
  (let [s (str/trim (str s))]
    (when-not (str/blank? s)
      (if-some [[_ a b] (re-matches
                         #"duration\(\s*([A-Za-z0-9_]+)\s*,\s*([A-Za-z0-9_]+)\s*\)"
                         s)]
        {:duration [(keyword a) (keyword b)]}
        (when (re-matches #"[A-Za-z0-9_]+" s)
          {:field (keyword s)})))))

(def ^:private period-ceiling
  "Each period unit's longest count: (months 3), the longest window,
  said in each unit."
  {"days" 92 "weeks" 13 "months" 3})

(defn parse-over
  "A measure's :over — (days n), (weeks n) or (months n), n a positive
  int — as [unit n]; nil when it is no period."
  [p]
  (when (and (sequential? p) (= 2 (count p)))
    (let [[u n] p]
      (when (and (symbol? u) (contains? period-ceiling (name u)) (int? n) (pos? n))
        [(name u) n]))))

(defn- decode ^String [^String s]
  (URLDecoder/decode s StandardCharsets/UTF_8))

(defn parse-among
  "A measure's :among, in the collection grammar a slot's :where speaks
  (\"state=shipped,delivered&owner=ana\"), as a sorted {key [value …]}:
  each key as the grammar names it (a suffixed key such as placed_at_gte
  stays whole), each comma list trimmed and sorted — the normal form the
  fingerprint hashes. nil when it is not a string or names nothing."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (not-empty
     (into (sorted-map)
           (keep (fn [pair]
                   (let [[k v] (str/split pair #"=" 2)]
                     (when-not (str/blank? k)
                       [(keyword (str/trim (decode k)))
                        (vec (sort (map str/trim (str/split (decode (or v "")) #","))))]))))
           (str/split s #"&")))))

(def ^:private grammar-suffixes
  ["_ne" "_gte" "_lte" "_after" "_before" "_set" "_contains"])

(defn among-fields
  "The fields a parsed :among filters by: each grammar key as the field
  it names, a suffixed key (placed_at_gte) read back to its field when
  the kind has no field of the whole name. field? says which names the
  kind has."
  [field? among]
  (into (sorted-set)
        (map (fn [k]
               (or (when (field? k) k)
                   (some (fn [sfx]
                           (let [n (name k)]
                             (when (str/ends-with? n sfx)
                               (let [f (keyword (subs n 0 (- (count n) (count sfx))))]
                                 (when (field? f) f)))))
                         grammar-suffixes)
                   k)))
        (keys among)))

(defn- where-problems [w]
  (try (expr/problems w)
       (catch Exception e [(ex-message e)])))

(defn shape-problems
  "One measure's [measure-shape] problems (§2, §8), each naming its key;
  empty when it is well-formed. Judged alone: what :of, :among and :at
  name on the kind it reads is the assembly gate's to judge."
  [m]
  (if-not (map? m)
    ["is a map of :label, :stat, :among, :at and :over"]
    (let [stat (spelled (:stat m))
          field (:field m)
          at (spelled (:at m))
          action (when at (transition-action at))
          o (:over m)
          over (parse-over o)
          strange (sort-by str (remove measure-keys (keys m)))]
      (cond-> []
        (seq strange)
        (conj (str "names " (str/join ", " (map pr-str strange))
                   ", not a measure key — one of "
                   (str/join " " (sort (map str measure-keys)))))

        (not (and (string? (:label m)) (not (str/blank? (:label m)))))
        (conj ":label is required: the sentence the dashboard and the notice print")

        (and (some? (:of m)) (not (keyword? (:of m))))
        (conj (str ":of " (pr-str (:of m)) " is not a kind keyword"))

        (not (some #{stat} stats))
        (conj (str ":stat " (pr-str (:stat m)) " is not one of " (str/join ", " stats)))

        (nil? (parse-among (:among m)))
        (conj (str ":among is required and not empty: a filter in the :of kind's "
                   "collection grammar, as \"state=shipped,delivered\" — a measure "
                   "says which rows it reads"))

        (and (some? (:where m)) (not= "rate" stat))
        (conj ":where is for :rate only — the other stats read every :among row")

        (and (= "rate" stat) (nil? (:where m)))
        (conj ":where is required on :rate: the expression its numerator meets")

        (and (some? (:where m)) (seq (where-problems (:where m))))
        (conj (str ":where is not an expression: " (first (where-problems (:where m)))))

        (and (field-stats stat) (nil? field))
        (conj (str ":field is required on :stat " stat
                   ": a number field or duration(<time-field>,<time-field>)"))

        (and (some? field) (not (field-stats stat)))
        (conj (str ":field is read by sum, median and p90 only, not " (pr-str (:stat m))))

        (and (some? field) (nil? (parse-field (spelled field))))
        (conj (str ":field " (pr-str field)
                   " is neither a field name nor duration(<time-field>,<time-field>)"))

        (nil? at)
        (conj ":at is required: a date or instant field, or transition:<action>")

        (and at (nil? action) (not (re-matches #"[A-Za-z0-9_]+" at)))
        (conj (str ":at " (pr-str at) " is neither a field name nor transition:<action>"))

        (and action (str/blank? action))
        (conj (str ":at " (pr-str at) " names no action after " transition-prefix))

        (nil? over)
        (conj (str ":over " (if (nil? o) "is required" (str (pr-str o) " is not a period"))
                   ": (days n), (weeks n) or (months n), n a positive int"))

        (and over (< (get period-ceiling (first over)) (second over)))
        (conj (str ":over " (pr-str o) " is past (months 3), the longest window"))

        (and (some? (:tell m)) (str/blank? (spelled (:tell m))))
        (conj (str ":tell " (pr-str (:tell m)) " is not a seat name"))

        (and (some? (:at-least m)) (not (pos-int? (:at-least m))))
        (conj (str ":at-least " (pr-str (:at-least m))
                   " is not a positive int: the fewest :among rows a window needs"))

        (and (some? (:default-target m)) (not (default-target? (:default-target m))))
        (conj (str ":default-target " (pr-str (:default-target m))
                   " is not {:op \">=\" or \"<=\", :target <number>}: the first goal the engine writes"))))))

(defn with-defaults
  "A kind's :measures with each measure's defaults written in — :of the
  declaring kind, :at-least 10 — so the fingerprint and every later
  reader see one spelling. A malformed entry passes as written, for
  [measure-shape] to name."
  [kind ms]
  (if (map? ms)
    (into {}
          (map (fn [[n m]]
                 [n (if (map? m)
                      (cond-> m
                        (nil? (:of m)) (assoc :of kind)
                        (nil? (:at-least m)) (assoc :at-least default-at-least))
                      m)]))
          ms)
    ms))

(defn tokens
  "The tokens a kind's measures are named by, `<kind>.<name>`, sorted —
  what discover lists per kind."
  [rmap]
  (when (map? (:measures rmap))
    (vec (sort (map #(str (name (:kind rmap)) "." (spelled %))
                    (keys (:measures rmap)))))))
