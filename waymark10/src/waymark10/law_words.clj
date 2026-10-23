(ns waymark10.law-words
  "The law page's words, spelled once (ticket 9935e3a3): how a door
  moves a row and what a scope entry opens, as factory10.law-text
  writes them on an org's law page and as a seat's page leads with
  them. Every fn reads an rdef and a seat's own entries; none reads a
  store."
  (:require [clojure.string :as str]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn words [k] (str/replace (name k) "_" " "))

(defn a-an [s] (str (if (re-find #"^[aeiou]" s) "an " "a ") s))

(defn capital [s]
  (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn either
  "a · a or b · a, b or c"
  [xs]
  (let [xs (vec xs)]
    (case (count xs)
      0 ""
      1 (first xs)
      (str (str/join ", " (pop xs)) " or " (peek xs)))))

(defn unique-rules
  "Each :unique group narrowed by :while, as the rule the kind's section
  states: 'At most one study per client and tax year is collecting,
  drafting or in review at a time.'"
  [r]
  (for [{:keys [fields while]} (t/unique-groups (:unique r))
        :when (seq while)]
    (str "At most one " (words (:kind r)) " per "
         (str/join " and " (map #(words (str/replace (name %) #"_id$" "")) fields))
         " is " (either (map words (filter (set while) (:states r))))
         " at a time.")))

(defn create-door? [r aname]
  (contains? (into (set (map name (:create-action-names r)))
                   (map (comp name :action))
                   (:creates r))
             (name aname)))

(defn- gerund [s]
  (cond (re-find #"[^e]e$" s) (str (subs s 0 (dec (count s))) "ing")
        (re-find #"^[^aeiou]*[aeiou][^aeiouwxy]$" s) (str s (subs s (dec (count s))) "ing")
        :else (str s "ing")))

(defn makes-lines
  "What one door makes as it lands (ticket 442ca14b), as the law page
  states it: 'Accepting a quote opens its job.'"
  [r aname]
  (let [a (some (fn [[n a]] (when (= (name n) (name aname)) a)) (:actions r))
        [verb & more] (str/split (words aname) #" ")]
    (for [{:keys [kind]} (:makes a)]
      (str (capital (str/join " " (cons (gerund verb) more)))
           " " (a-an (words (:kind r))) " opens its " (words kind) "."))))

(defn move-phrase
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

(defn wake-phrase
  "One wake, as the door's own move reads in its kind's section; a kind
  `rdef-of` does not answer is named with the door, never a guessed
  past tense."
  [rdef-of kind a]
  (if-some [r (rdef-of kind)]
    (move-phrase r a)
    (str (a-an (words kind)) " goes through " (words a))))

(defn scope-phrase
  "One scope entry: 'qualify, engage or decline a prospect', 'read task',
  with its filter said after it."
  [{:keys [kind actions filter]}]
  (let [k (words kind)]
    (str (if (seq actions) (str (either (map words actions)) " " (a-an k)) (str "read " k))
         (when (seq filter)
           (str " (only where "
                (str/join " and " (map (fn [[f v]] (str (words f) " is " v)) filter))
                ")")))))

(defn from-states
  "The states of `r`, in its declared order, that one of `actions`
  (non-create doors, by name) leaves: where a row waits for this entry."
  [r actions]
  (let [named (set (map name actions))
        froms (into #{}
                    (comp (filter (fn [[n _]] (and (named (name n))
                                                   (not (create-door? r n)))))
                          (mapcat (fn [[_ a]] (:from a)))
                          (map name))
                    (:actions r))]
    (filterv #(froms (name %)) (:states r))))
