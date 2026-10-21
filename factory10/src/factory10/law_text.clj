(ns factory10.law-text
  "The law a person approves, in sentences (ticket 59f0879f, M2f).

  `law-text` reads an org's declarations and seed and writes a short
  page: each kind's states and doors in plain words, every guard's
  sentence, every door that waits for a person's tap, every confirm
  door's consequence, each seat's charter, scope in words and weekly
  budget, and the total weekly budget. An org's pull request carries
  this page, so the person reviews English, not Clojure.

  The seed is read from the org's directory; the org's module
  (`<org>.org/resources`) is resolved on the classpath, as the
  factory's :test alias roots orgs/example."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [waymark10.server.seed :as seed]))

(set! *warn-on-reflection* true)

;; ── words ───────────────────────────────────────────────────────────

(defn- words [k] (str/replace (name k) "_" " "))

(defn- a-an [s] (str (if (re-find #"^[aeiou]" s) "an " "a ") s))

(defn- capital [s]
  (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn- either
  "a · a or b · a, b or c"
  [xs]
  (let [xs (vec xs)]
    (case (count xs)
      0 ""
      1 (first xs)
      (str (str/join ", " (pop xs)) " or " (peek xs)))))

(defn- dollars [x]
  (let [^java.math.BigDecimal d (bigdec x)]
    (str "$" (.toPlainString (.setScale d 2 java.math.RoundingMode/HALF_UP)))))

(defn- every-words [secs]
  (cond (= 3600 secs) "every hour"
        (= 86400 secs) "every day"
        (= 604800 secs) "every week"
        (zero? (mod secs 3600)) (str "every " (quot secs 3600) " hours")
        (zero? (mod secs 60)) (str "every " (quot secs 60) " minutes")
        :else (str "every " secs " seconds")))

;; ── the kinds ───────────────────────────────────────────────────────

(defn- guard-of [g] (if (var? g) @g g))

(defn- guard-lines [guards]
  (for [g (map guard-of guards)
        :let [s (or (:explain g) (some-> (:name g) words))]
        :when s]
    (str "  - " (cond (:hold g) "Waits for a person's tap: "
                      (= :warning (:severity g)) "Warning: "
                      :else "Rule: ")
         s)))

(defn- consequence [safety]
  (let [c (:consequence safety)]
    (if (map? c) (str/join " " (vals c)) c)))

(defn- safety-lines [safety]
  (cond-> []
    (:confirm safety)
    (conj (str "  - It asks first" (some->> (consequence safety) (str ": ")) (when-not (consequence safety) ".")))
    (false? (:reversible safety))
    (conj (str "  - It cannot be undone" (if-some [w (:one-way safety)] (str ": " w) ".")))))

(defn- doors
  "Every door of a kind as [door-name guards safety], create first: the
  ordinary create, then each create door :creates declares."
  [r]
  (concat (for [c (sort (:create-action-names r))]
            [c (:create-guards r) nil])
          (for [d (:creates r)]
            [(:action d) (:guards d) nil])
          (for [[aname a] (:actions r)]
            [aname (:guards a) (:safety a)])))

(defn- create-door? [r aname]
  (contains? (into (set (map name (:create-action-names r)))
                   (map (comp name :action))
                   (:creates r))
             (name aname)))

(defn- move-phrase
  "How one door moves a row, in the words of the kind's section: 'a new
  prospect becomes qualified'; a create door is 'a prospect is written'."
  [r aname]
  (let [k (words (:kind r))
        a (some (fn [[n a]] (when (= (name n) (name aname)) a)) (:actions r))
        from (either (map words (filter (set (:from a)) (:states r))))
        to (:to a)]
    (if (create-door? r aname)
      (str (a-an k) " is written")
      (str (a-an (if (str/blank? from) k (str from " " k))) " "
           (cond (not (keyword? to)) "moves on"
                 (= #{to} (set (:from a))) (str "stays " (words to))
                 :else (str "becomes " (words to)))))))

(defn- kind-section [r]
  (let [k (words (:kind r))
        ss (:states r)
        named (fn [xs] (either (map words (filter (set xs) ss))))
        initial (words (:initial r))
        ends (named (:terminal r))
        create? (into (set (:create-action-names r)) (map :action) (:creates r))
        engine-only? (into #{}
                           (comp (filter #(contains? (:by %) :engine))
                                 (map :action))
                           (:creates r))]
    (concat
     [(str "## " (capital k))
      ""
      (str (capital (a-an k)) " is " (either (map words ss)) ". It starts " initial
           (when (seq ends) (str ", and it ends " ends)) ".")
      ""]
     (mapcat
      (fn [[aname guards safety]]
        (concat
         [(str "- **" (name aname) "**: " (move-phrase r aname)
               (if (create? aname) (str ", and it starts " initial ".") "."))]
         (when (engine-only? aname)
           ["  - Only the engine walks it: no person or agent may."])
         (guard-lines guards)
         (safety-lines safety)))
      (doors r)))))

(defn- held-section [rs]
  (let [held (for [r rs
                   [aname guards] (doors r)
                   g (map guard-of guards)
                   :when (:hold g)]
               (str "- " (words (:kind r)) " **" (name aname) "**: " (:explain g)))]
    (into ["## Doors a person taps" ""]
          (if (seq held)
            held
            ["No door here waits for a person's tap: every door opens on its rules alone."]))))

(defn- decided-section
  "The person's answers to the org audit's questions (ticket e1ed02c7):
  each :decided sentence, a kind's states first, then its doors. A held
  door is told under 'Doors a person taps' and not again here."
  [rs]
  (let [said (for [r rs
                   :let [k (words (:kind r))
                         ds (when (map? (:decided r)) (:decided r))]
                   line (concat
                         (for [s (:states r)
                               :let [d (some (fn [[st d]] (when (= (name s) (name st)) d)) ds)]
                               :when d]
                           (str "- " k ", while " (words s) ": " d))
                         (for [[aname a] (:actions r)
                               :when (and (:decided a)
                                          (not-any? #(:hold (guard-of %)) (:guards a)))]
                           (str "- " k " **" (name aname) "**: " (:decided a)
                                (when-some [why (get-in a [:decided-held :none])]
                                  (str " (No person taps it: " why ")")))))]
               line)]
    (into ["## What you decided" ""]
          (if (seq said)
            said
            ["Nothing here is written down as decided yet."]))))

(defn- confirm-section [rs]
  (let [asks (for [r rs
                   [aname a] (:actions r)
                   :when (:confirm (:safety a))]
               (str "- " (words (:kind r)) " **" (name aname) "**: "
                    (or (consequence (:safety a)) "it asks before it acts.")))]
    (into ["## Doors that ask before they act" ""]
          (if (seq asks)
            asks
            ["No door here asks for a confirmation before it acts."]))))

;; ── the seats ───────────────────────────────────────────────────────

(defn- seat-of
  "A seed's seat entry as the seat map: a whole :seat, or a factorykit
  template filled with its params, as the seed loader fills it."
  [{:keys [template params seat]}]
  (if template
    (let [t (-> (str "seats/" template ".edn") io/resource slurp edn/read-string)]
      (seed/filled (:seat t) (or params {}) (:template t)))
    seat))

(defn- scope-line [{:keys [kind actions filter]}]
  (let [k (words kind)]
    (str "- " (if (seq actions) (str (either actions) " " (a-an k)) (str "read " k))
         (when (seq filter)
           (str " (only where "
                (str/join " and " (map (fn [[f v]] (str (words f) " is " v)) filter))
                ")")))))

(defn- wake-phrase
  "One wake, as the door's own move reads in its kind's section; a kind
  this org does not declare is named with the door, never a guessed
  past tense."
  [kinds kind a]
  (if-some [r (get kinds (name kind))]
    (move-phrase r a)
    (str (a-an (words kind)) " goes through " (words a))))

(defn- seat-lines [plural kinds seat]
  (let [{:keys [walk cadence_seconds rows_per_firing wake_on scope
                budget_usd_per_week charter]} seat
        wakes (remove nil?
                      [(some-> cadence_seconds every-words)
                       (when (seq wake_on)
                         (str "when "
                              (either (for [{:keys [kind actions]} wake_on
                                            a actions]
                                        (wake-phrase kinds kind a)))))])]
    (concat
     [(str "### " (:name seat)) ""]
     (when (or walk (seq wakes))
       [(str/trim
         (str (when walk
                (str "Works through the " (plural walk)
                     (case rows_per_firing
                       nil ""
                       1 ", one at a time"
                       (str ", " rows_per_firing " at a time"))
                     "."))
              (when (seq wakes)
                (str " Wakes " (str/join ", and " wakes) "."))))
        ""])
     (when (seq scope)
       (concat ["It may:"] (map scope-line scope) [""]))
     [(if budget_usd_per_week
        (str "Spends at most " (dollars budget_usd_per_week) " a week.")
        "Has no weekly budget set.")]
     (when charter
       ["" "Its charter:" "" (str "> " (str/replace (str/trim charter) "\n" "\n> "))]))))

(defn- seats-section [rs seats]
  (let [plurals (into {} (map (fn [r] [(name (:kind r)) (words (:plural r))])) rs)
        plural #(get plurals (name %) (str (words %) "s"))
        kinds (into {} (map (fn [r] [(name (:kind r)) r])) rs)]
    (into ["## Seats" ""]
          (if (seq seats)
            (apply concat (interpose [""] (map #(seat-lines plural kinds %) seats)))
            ["No seat works here."]))))

(defn- budget-section [seats]
  (let [budgets (keep :budget_usd_per_week seats)]
    ["## Budget" ""
     (cond
       (empty? seats) "No seat works here, so nothing is spent."
       (empty? budgets) "No seat has a weekly budget set."
       :else (str "All seats together spend at most "
                  (dollars (reduce + 0M (map bigdec budgets))) " a week"
                  (when (< (count budgets) (count seats))
                    ", besides the seats with no budget set")
                  "."))]))

;; ── the page ────────────────────────────────────────────────────────

(defn- read-seed [org-dir]
  (let [f (->> (.listFiles (io/file org-dir "resources"))
               (sort)
               (map #(io/file % "seed.edn"))
               (filter #(.isFile ^java.io.File %))
               first)]
    (when-not f
      (throw (ex-info (str "no seed under " org-dir "/resources/<org>/seed.edn")
                      {:org-dir (str org-dir)})))
    (edn/read-string (slurp f))))

(defn- org-resources
  "The org's module is `<org>.org`, its name verbatim, underscores
  included, as the engine boots it and conform judges it."
  [org]
  ((requiring-resolve (symbol (str org ".org") "resources"))))

(defn law-text
  "The org at `org-dir` as the page a person approves: markdown, a
  short read for a non-programmer."
  [org-dir]
  (let [seed (read-seed org-dir)
        org (:org seed)
        rs (org-resources org)
        seats (mapv seat-of (:seats seed))
        label (or (:label (first (:domains seed))) org)]
    (str (str/join
          "\n"
          (apply concat
                 (interpose
                  [""]
                  (concat
                   [[(str "# The law of " label)
                     ""
                     (str "This is what you approve when you merge: each kind of record this org keeps, "
                          "how it moves, what each move asks first, which moves wait for a person, "
                          "and which seats work here and what they may spend.")]]
                   (map kind-section rs)
                   [(held-section rs)
                    (decided-section rs)
                    (confirm-section rs)
                    (seats-section rs seats)
                    (budget-section seats)]))))
         "\n")))

(defn -main
  "Print the law of the org at `org-dir`, for a pull request's description."
  [org-dir]
  (print (law-text org-dir))
  (flush))
