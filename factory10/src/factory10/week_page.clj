(ns factory10.week-page
  "A plan's first week in words (ticket 3f448fe7, piece 1 of d8a521a8).

  The owner reviews a plan by reading its simulated week. `week-page`
  builds that page from the plan alone: its `dry_week` (each call's
  day, as, kind, action and outcome), its `first_week_scene`'s
  `:week_script` (each call's :name or :row, :input and :why, matched to
  the dry week by order within the day) and its `law_page` (each held
  door's sentence, under 'Doors a person taps'). The findings doors
  write it as `week_page` wherever they write `dry_week`, and the plan
  shows it like its law page.

  With the plan's `week_labels` (factory10.week-labels, ticket
  4eda448b) it reads in the org's words: a row by its create's title
  field, a door by its display label, at most two labelled values, and
  only the org's money fields in the tally. Without them it reads as
  before."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [jsonista.core :as j])
  (:import (java.math BigDecimal RoundingMode)
           (java.time LocalDate)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(set! *warn-on-reflection* true)

(def ^:private week-page-cap 40000)

;; ── words ───────────────────────────────────────────────────────────

(defn- text [x] (if (keyword? x) (name x) (str x)))

(defn- humanize
  "A row's name in words: birch-2025 → Birch 2025."
  [x]
  (->> (str/split (text x) #"[-_\s]+")
       (remove str/blank?)
       (map str/capitalize)
       (str/join " ")))

(defn- label
  "A door's name as its label: send_invoice → Send invoice."
  [action]
  (let [w (str/replace (text action) "_" " ")]
    (if (str/blank? w) w (str (str/upper-case (subs w 0 1)) (subs w 1)))))

(defn- words [k] (str/replace (text k) "_" " "))

(defn- plural [w n]
  (cond (= 1 n) w
        (re-find #"[^aeiou]y$" w) (str (subs w 0 (dec (count w))) "ies")
        (str/ends-with? w "s") w
        :else (str w "s")))

(defn- past [w] (if (str/ends-with? w "e") (str w "d") (str w "ed")))

(defn- money-key? [k]
  (let [n (text k)]
    (or (contains? #{"amount" "total" "fee"} n) (str/ends-with? n "_usd"))))

(defn- money ^BigDecimal [v]
  (try (when (some? v) (bigdec v)) (catch Exception _ nil)))

(defn- dollars [^BigDecimal d]
  (let [w (.setScale d 0 RoundingMode/HALF_UP)]
    (if (== d w)
      (String/format Locale/US "$%,d" (to-array [(.toBigInteger w)]))
      (String/format Locale/US "$%,.2f" (to-array [d])))))

(defn- local-date ^LocalDate [s]
  (try (LocalDate/parse (text s)) (catch Exception _ nil)))

(defn- fmt [^LocalDate d ^String pattern]
  (.format d (DateTimeFormatter/ofPattern pattern Locale/ENGLISH)))

(defn- date-input [v]
  (when (and (string? v) (re-matches #"\d{4}-\d{2}-\d{2}" v))
    (some-> (local-date v) (fmt "MMM d"))))

;; ── what the plan carries ───────────────────────────────────────────

(defn- scene-of
  "The plan's first_week_scene edn as data, or nil."
  [scene]
  (when (string? scene)
    (try (let [d (edn/read-string {:default tagged-literal} scene)]
           (when (map? d) d))
         (catch Exception _ nil))))

(defn- holds-of
  "The law page's 'Doors a person taps' → {[kind action] sentence}."
  [law]
  (->> (str/split-lines (str law))
       (drop-while #(not= "## Doors a person taps" (str/trim %)))
       rest
       (take-while #(not (str/starts-with? % "## ")))
       (keep (fn [l]
               (when-some [[_ kind action s] (re-matches #"- (.+?) \*\*(.+?)\*\*: ?(.*)" l)]
                 (when-not (str/blank? s)
                   [[(str/replace kind " " "_") action] (str/trim s)]))))
       (into {})))

(defn- org-name [law]
  (some->> (str/split-lines (str law)) first (re-matches #"# The law of (.+)") second str/trim))

;; ── the org's own words (ticket 4eda448b) ───────────────────────────

(defn- labels-of
  "The plan's `week_labels`, factory10.week-labels' JSON, as {kind
  {:title :fields :doors :money}}; nil when it carries none."
  [labels]
  (let [d (cond (map? labels) labels
                (string? labels) (try (j/read-value labels j/keyword-keys-object-mapper)
                                      (catch Exception _ nil)))]
    (when (and (map? d) (seq d)) d)))

(defn- kind-labels [labels kind] (get labels (keyword (text kind))))

(defn- money-fields [kl] (into #{} (map text) (:money kl)))

(defn- door-label
  "The door's own display label, else its name as a label."
  [kl action]
  (or (some-> (get-in kl [:doors (keyword (text action))]) text str/trim not-empty)
      (label action)))

(defn- field-label
  "A field's label mid-sentence: Estimated credit → estimated credit;
  its name without _usd or _cents when the form gives none."
  [kl k]
  (let [l (or (some-> (get-in kl [:fields (keyword (text k))]) text str/trim not-empty)
              (words (str/replace (text k) #"_(usd|cents)$" "")))]
    (if (or (str/blank? l)
            (and (< 1 (count l)) (Character/isUpperCase (.charAt ^String l 1))))
      l
      (str (str/lower-case (subs l 0 1)) (subs l 1)))))

(defn- labelled
  "At most two of a call's inputs as '<field label> <value>': money
  first, then dates. A date that is the day's own is dropped, and no
  value repeats."
  [kl input dt]
  (let [today (some-> dt (fmt "MMM d"))
        ms (money-fields kl)
        cash (keep (fn [[k v]]
                     (when (or (money-key? k) (contains? ms (text k)))
                       (some->> (money v) dollars (vector k))))
                   input)
        days (keep (fn [[k v]]
                     (when-some [s (date-input v)]
                       (when (not= s today) [k s])))
                   input)]
    (->> (concat cash days)
         (reduce (fn [acc [_ v :as kv]] (if (some #(= v (second %)) acc) acc (conj acc kv))) [])
         (take 2)
         (map (fn [[k v]] (str (field-label kl k) " " v))))))

(defn- row-name
  "A created row's name: its create input's title field, a slug there
  said as that row's name. A kind that carries money and names a client
  reads '<client>'s <kind>'."
  [labels names {:keys [kind input]}]
  (when (map? input)
    (let [kl (kind-labels labels kind)
          said (fn [v] (when-some [s (some-> v text str/trim not-empty)] (get names s s)))
          client (said (:client input))]
      (if (and client (seq (:money kl)) (not= "client" (text kind)))
        (str client "'s " (words kind))
        (some->> (:title kl) text not-empty keyword (get input) said)))))

(defn- row-names
  "Each created row's slug → its name, in script order, so a later call
  on `:row` names it as its create did; nil without labels."
  [labels scripted]
  (when labels
    (reduce (fn [m sc]
              (let [slug (some-> (:name sc) text not-empty)
                    n (when slug (row-name labels m sc))]
                (if n (assoc m slug n) m)))
            {}
            (filter map? (mapcat #(get scripted %) (sort (filter number? (keys scripted))))))))

(defn- money-said
  "The tally's money in the kind's noun: $9,600 invoiced for an invoice,
  else $X in <plural>."
  [sum mk]
  (if (= "invoice" mk)
    (str (dollars sum) " invoiced")
    (str (dollars sum) " in " (plural (words mk) 2))))

;; ── the page ────────────────────────────────────────────────────────

(defn- held? [c] (= "held" (text (:outcome c))))

(defn- landed? [c] (not (contains? #{"held" "refused"} (text (:outcome c)))))

(defn- who [as]
  (case (text as)
    ("person" "owner") "You"
    "world" "Outside"
    "AI"))

(defn- first-money [input]
  (when (map? input)
    (some (fn [[k v]] (when (money-key? k) (money v))) input)))

(defn- bullet [holds labels names dt [{:keys [as kind action] :as c} sc]]
  (let [kl (kind-labels labels kind)
        slug (or (:name sc) (:row sc))
        row (if labels
              (some->> slug text (get names))
              (some-> slug humanize not-empty))
        input (when (map? (:input sc)) (:input sc))
        extras (if labels
                 (labelled kl input dt)
                 (keep (fn [[k v]]
                         (if (money-key? k)
                           (some-> (money v) dollars)
                           (date-input v)))
                       input))
        door (if labels (door-label kl action) (label action))
        hold (or (get holds [(text kind) (text action)])
                 (some (fn [[[_ a] s]] (when (= a (text action)) s)) holds))
        why (some-> (:why sc) str str/trim not-empty)]
    (cond-> [(str "- **" (who as) "** "
                  (str/join " · " (concat [door] (when row [row]) extras))
                  (when (held? c)
                    (if hold (str " — *waits on you:* " hold) " — *waits on you.*")))]
      why (conj (str "  " why)))))

(defn- tally
  "The week's tally: the goal reached, the money invoiced, the taps
  asked of the owner, a zero part left out; nil when all are zero. With
  the org's labels only its money fields count."
  [goal paired labels]
  (let [calls (map first paired)
        {gk :kind gs :state} goal
        gk (some-> gk text not-empty)
        gs (some-> gs text not-empty)
        reaches? (fn [{:keys [kind action outcome] :as c}]
                   (let [a (text action)]
                     (and (= gk (text kind)) (landed? c)
                          (or (= gs (text outcome))
                              (= gs a)
                              (and (seq a) (str/starts-with? gs (str/replace a #"e$" "")))))))
        reached (if (and gk gs) (count (filter reaches? calls)) 0)
        amount (if labels
                 (fn [[c sc]]
                   (let [ms (money-fields (kind-labels labels (:kind c)))]
                     (when (map? (:input sc))
                       (some (fn [[k v]] (when (contains? ms (text k)) (money v))) (:input sc)))))
                 (fn [[_ sc]] (first-money (:input sc))))
        paid (filter (fn [[c :as p]] (and (landed? c) (amount p))) paired)
        mk (some-> (first paid) first :kind text)
        sum (reduce + 0M (keep (fn [[c :as p]] (when (= mk (text (:kind c))) (amount p))) paid))
        taps (count (filter held? calls))
        parts (cond-> []
                (pos? reached) (conj (str reached " " (plural (words gk) reached) " " (words gs)))
                (and mk (pos? sum)) (conj (if labels
                                            (money-said sum mk)
                                            (str (dollars sum) " " (past (words mk)))))
                (pos? taps) (conj (str taps " tap" (when (not= 1 taps) "s") " from you")))]
    (when (seq parts) (str/join " · " parts))))

(defn week-page
  "The plan's first week as markdown, from its data's `dry_week`,
  `first_week_scene` and `law_page`; nil when it played no call."
  [data]
  (let [d (walk/keywordize-keys (or data {}))
        calls (filter map? (:dry_week d))]
    (when (seq calls)
      (let [doc (scene-of (:first_week_scene d))
            ws (when (map? (:week_script doc)) (:week_script doc))
            day-one (local-date (or (:day-one ws) (:day_one ws) (:day_one doc)))
            scripted (into {} (keep (fn [x] (when (map? x) [(:day x) (vec (:calls x))])))
                           (:days ws))
            by-day (group-by :day calls)
            days (sort (filter number? (keys by-day)))
            pairs (fn [k] (map-indexed (fn [i c] [c (get-in scripted [k i])]) (get by-day k)))
            date-of (fn [k] (when day-one (.plusDays day-one (long (dec k)))))
            law (:law_page d)
            holds (holds-of law)
            labels (labels-of (:week_labels d))
            names (row-names labels scripted)
            org (or (org-name law) (some-> (:name d) humanize not-empty) "the org")
            dates (when (and day-one (seq days))
                    (str (fmt (date-of (first days)) "EEE MMM d")
                         (when (< 1 (count days))
                           (str " – " (fmt (date-of (last days)) "EEE MMM d")))))
            line (tally (when (map? (:goal ws)) (:goal ws)) (mapcat pairs days) labels)
            blocks (concat
                    [[(str "# A week at " org)]]
                    (when dates [[dates]])
                    (when line [[line]])
                    (for [k days]
                      (into [(if-some [dt (date-of k)]
                               (str "## " (fmt dt "EEEE") " · " (fmt dt "MMM d"))
                               (str "## Day " k))
                             ""]
                            (mapcat #(bullet holds labels names (date-of k) %) (pairs k)))))
            page (str (str/join "\n\n" (map #(str/join "\n" %) blocks)) "\n")]
        (if (> (count page) week-page-cap)
          (str (subs page 0 (dec week-page-cap)) "…")
          page)))))
