(ns factory10.week-labels
  "The org's own words for its first week (ticket 4eda448b).

  `week-labels` loads an org's kinds and answers, per kind, the field a
  row is named by, the labels its forms give its fields and doors, and
  the number fields that are money. CI prints it beside the law page,
  the forge pass writes it onto the plan as `week_labels`, and
  `factory10.week-page` reads it to say rows, values and doors as the
  org does. A failure prints {}, and costs only the labels.

  The seed is read from the org's directory and the org's module
  (`<org>.org/resources`) resolved on the classpath, as law-text does."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as j]
            [waymark10.schema :as schema]))

(set! *warn-on-reflection* true)

(def ^:private title-fields [:client :company :name :title])

(def ^:private number-heads #{:int :double :decimal :number :float :waymark/money})

(defn- fields-of
  "key → entry of a :map form, or nil when the form is no map."
  [form]
  (when form
    (try (schema/entry-map form) (catch Exception _ nil))))

(defn- head [form]
  (let [f (if (and (vector? form) (= :maybe (first form))) (second form) form)]
    (if (vector? f) (first f) f)))

(defn- said [x]
  (when (and (string? x) (not (str/blank? x))) x))

(defn- field-label [props]
  (some said [(:title props) (:label props) (get-in props [:x-display :label])]))

(defn- money-name?
  "amount, total or fee, without a _usd or _cents suffix."
  [k]
  (contains? #{"amount" "total" "fee"} (str/replace (name k) #"_(usd|cents)$" "")))

(defn kind-labels
  "One kind's labels: {:title :fields :doors :money}. The title is the
  kind's :display :title, else the first of client, company, name or
  title among its fields; a field's label is the one its form shows,
  the kind's own schema before a door's input."
  [r]
  (let [doors (concat (:actions r) (map (juxt :action identity) (:creates r)))
        entries (mapcat fields-of (cons (:schema r) (map (comp :input second) doors)))
        named (into #{} (map key) entries)
        display (get-in r [:display :title])]
    {:title (or (when (or (keyword? display) (string? display)) (name display))
                (some #(when (contains? named %) (name %)) title-fields))
     :fields (reduce (fn [m [k e]]
                       (let [l (field-label (:properties e))]
                         (if (and l (not (contains? m (name k)))) (assoc m (name k) l) m)))
                     (sorted-map) entries)
     :doors (into (sorted-map)
                  (keep (fn [[a d]]
                          (when-let [l (and (map? d) (said (get-in d [:display :label])))]
                            [(name a) l])))
                  doors)
     :money (vec (into (sorted-set)
                       (comp (filter (fn [[k e]] (and (number-heads (head (:schema e))) (money-name? k))))
                             (map (comp name key)))
                       entries))}))

(defn labels
  "Every kind of `rs` → its labels, keyed by the kind's name."
  [rs]
  (into (sorted-map) (map (fn [r] [(name (:kind r)) (kind-labels r)])) rs))

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

(defn week-labels
  "The labels of the org at `org-dir`, as one line of JSON."
  [org-dir]
  (let [org (:org (read-seed org-dir))
        rs ((requiring-resolve (symbol (str org ".org") "resources")))]
    (j/write-value-as-string (labels rs))))

(defn -main
  "Print the labels of the org at `org-dir`; {} when they do not load."
  [org-dir]
  (println (try (week-labels org-dir)
                (catch Throwable e
                  (binding [*out* *err*]
                    (println "week-labels:" (ex-message e)))
                  "{}")))
  (flush))
