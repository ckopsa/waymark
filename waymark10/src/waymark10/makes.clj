(ns waymark10.makes
  "The next row of the work (ticket 442ca14b): the `:makes` a door
  declares, read at the def site and run as the door lands.

  An entry is {:kind <kind> :input {<field> <expr>}}. As the door lands,
  in its own transaction, it creates one row of that kind through the
  kind's create door, with each :input form read over the moving row —
  (self) or (id) its id, (data …), (input …), (follow …). The made
  kind's ref back to the moving kind is filled in when the input leaves
  it out. The create's guards judge the made row as ever, and a refusal
  refuses the whole move."
  (:require [waymark10.expr :as expr]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- spelled
  "(id) is the moving row's id, as (self) reads it."
  [form]
  (if (= '(id) form) '(self) form))

(defn normalize
  "An action's :makes → its entries, each {:kind :input} with every
  input form well formed. Idempotent. A malformed entry is a definition
  error naming the door."
  [kind aname makes]
  (let [bad #(throw (t/definition-error
                     (str (name kind) "/" (name aname) " :makes: " %)
                     {:check :makes}))]
    (when-not (sequential? makes)
      (bad "a vector of {:kind <kind> :input {<field> <expr>}}"))
    (mapv (fn [m]
            (when-not (map? m)
              (bad (str "each entry is {:kind <kind> :input {<field> <expr>}}; got " (pr-str m))))
            (when-some [extra (seq (dissoc m :kind :input))]
              (bad (str "entry carries unknown key(s) " (vec (map first extra)))))
            (when-not (keyword? (:kind m))
              (bad "each entry names its :kind as a keyword"))
            (when-not (or (nil? (:input m)) (map? (:input m)))
              (bad ":input is a map of field to expression"))
            {:kind (:kind m)
             :input (into {}
                          (map (fn [[f form]]
                                 (when-not (keyword? f)
                                   (bad (str ":input names its fields as keywords; got " (pr-str f))))
                                 (let [form (spelled form)]
                                   (when-some [p (first (expr/problems form))]
                                     (bad (str (name f) " " p)))
                                   [f form])))
                          (:input m))})
          makes)))

(defn back-ref
  "The one field of `target`'s create schema that names a row of
  `source-kind`, or nil when it has none or several."
  [source-kind target]
  (let [refs (filter #(and (= source-kind (:kind %)) (not (:listed %)))
                     (schema/ref-fields (or (:create-schema target) (:schema target))))]
    (when (= 1 (count refs))
      (:field (first refs)))))

(defn input-of
  "The made row's create input: each :input form over `scope` (the
  moving row's), a form that answers nil or unknown left out, and the
  ref back to `source-kind` filled with (:self scope) when the input
  leaves it out."
  [m source-kind target scope]
  (let [back (back-ref source-kind target)
        body (into {}
                   (keep (fn [[f form]]
                           (let [v (expr/evaluate (expr/normalize form) scope)]
                             (when-not (or (nil? v) (expr/unknown? v))
                               [f v]))))
                   (:input m))]
    (cond-> body
      (and back (not (contains? body back))) (assoc back (:self scope)))))
