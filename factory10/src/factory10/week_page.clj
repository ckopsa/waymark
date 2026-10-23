(ns factory10.week-page
  "A plan's first week in words (ticket 3f448fe7, piece 1 of d8a521a8).

  The owner reviews a plan by reading its simulated week. `week-page`
  builds that page from the plan alone: its `dry_week` (each call's
  day, as, kind, action and outcome), its `first_week_scene`'s
  `:week_script` (each call's :name or :row, :input and :why, matched to
  the dry week by order within the day) and its `law_page` (each held
  door's sentence, under 'Doors a person taps'). The findings doors
  write it as `week_page` wherever they write `dry_week`, and the plan
  shows it like its law page."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk])
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

(defn- bullet [holds [{:keys [as kind action] :as c} sc]]
  (let [row (some-> (or (:name sc) (:row sc)) humanize not-empty)
        input (when (map? (:input sc)) (:input sc))
        extras (keep (fn [[k v]]
                       (if (money-key? k)
                         (some-> (money v) dollars)
                         (date-input v)))
                     input)
        hold (or (get holds [(text kind) (text action)])
                 (some (fn [[[_ a] s]] (when (= a (text action)) s)) holds))
        why (some-> (:why sc) str str/trim not-empty)]
    (cond-> [(str "- **" (who as) "** "
                  (str/join " · " (concat [(label action)] (when row [row]) extras))
                  (when (held? c)
                    (if hold (str " — *waits on you:* " hold) " — *waits on you.*")))]
      why (conj (str "  " why)))))

(defn- tally
  "The week's tally: the goal reached, the money invoiced, the taps
  asked of the owner, a zero part left out; nil when all are zero."
  [goal paired]
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
        paid (filter (fn [[c sc]] (and (landed? c) (first-money (:input sc)))) paired)
        mk (some-> (first paid) first :kind text)
        sum (reduce + 0M (keep (fn [[c sc]] (when (= mk (text (:kind c))) (first-money (:input sc)))) paid))
        taps (count (filter held? calls))
        parts (cond-> []
                (pos? reached) (conj (str reached " " (plural (words gk) reached) " " (words gs)))
                (and mk (pos? sum)) (conj (str (dollars sum) " " (past (words mk))))
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
            org (or (org-name law) (some-> (:name d) humanize not-empty) "the org")
            dates (when (and day-one (seq days))
                    (str (fmt (date-of (first days)) "EEE MMM d")
                         (when (< 1 (count days))
                           (str " – " (fmt (date-of (last days)) "EEE MMM d")))))
            line (tally (when (map? (:goal ws)) (:goal ws)) (mapcat pairs days))
            blocks (concat
                    [[(str "# A week at " org)]]
                    (when dates [[dates]])
                    (when line [[line]])
                    (for [k days]
                      (into [(if-some [dt (date-of k)]
                               (str "## " (fmt dt "EEEE") " · " (fmt dt "MMM d"))
                               (str "## Day " k))
                             ""]
                            (mapcat #(bullet holds %) (pairs k)))))
            page (str (str/join "\n\n" (map #(str/join "\n" %) blocks)) "\n")]
        (if (> (count page) week-page-cap)
          (str (subs page 0 (dec week-page-cap)) "…")
          page)))))
