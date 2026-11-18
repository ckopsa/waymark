(ns waymark10.worksheet-decl
  "The :worksheet declaration's def-site gate, out of resource.clj so
  that namespace stays under its size ceiling (scripts/ns-size-check.sh).
  resource/validate! calls check! the way it calls retention/check!."
  (:require [clojure.string :as str]
            [waymark10.schema :as schema]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def ^:private column-keys
  #{:field :action :param :ref :on-set :on-clear :create-only :label})

(defn check!
  "The worksheet declaration's def-site gate: every column names a
  declared field; an editable column's :action (or :on-set/:on-clear
  actions) is declared, and its :param — the invoke input key the
  cell value rides — names one of that action's input entries; a
  :label is a non-blank string naming exactly one column. The
  offline round-trip is law-shaped enough to refuse typos at boot,
  not at the first upload."
  [rmap]
  (when-some [ws (:worksheet rmap)]
    (let [err (fn [msg] (throw (t/definition-error
                                (str (some-> (:kind rmap) name)
                                     " [worksheet] " msg))))
          fields (set (schema/entry-keys (:schema rmap)))
          actions (:actions rmap)
          check-invoke
          (fn [field action param label]
            (let [a (get actions action)]
              (when (nil? a)
                (err (str (name field) ": " label " action " action
                          " is not declared")))
              (when param
                (if (:input a)
                  (when-not (contains? (set (schema/entry-keys (:input a)))
                                       param)
                    (err (str (name field) ": " label " param " param
                              " is not an input of " action)))
                  (err (str (name field) ": " label " carries param " param
                            " but " action " takes no input"))))))]
      (when-not (and (map? ws) (vector? (:columns ws)) (seq (:columns ws)))
        (err ":worksheet is {:columns [{:field …} …] :create bool?}"))
      (when-some [k (some #(when-not (contains? #{:columns :create} %) %)
                          (keys ws))]
        (err (str "unknown key " k " — worksheet keys are [:columns :create]")))
      (doseq [col (:columns ws)]
        (when-not (map? col)
          (err "every column is a map"))
        (when-some [k (some #(when-not (contains? column-keys %) %)
                            (keys col))]
          (err (str "unknown column key " k " — column keys are "
                    (vec (sort column-keys)))))
        (when-not (contains? fields (:field col))
          (err (str (some-> (:field col) name (or "(no :field)"))
                    ": names no declared field")))
        (when (and (:action col) (or (:on-set col) (:on-clear col)))
          (err (str (name (:field col))
                    ": :action and :on-set/:on-clear are two grammars — pick one")))
        (when (and (:create-only col) (or (:action col) (:on-set col)))
          (err (str (name (:field col))
                    ": :create-only columns take no edit action")))
        (when-some [a (:action col)]
          (check-invoke (:field col) a (or (:param col) (:field col))
                        ":action"))
        (when-some [os (:on-set col)]
          (check-invoke (:field col) (:action os) (:param os) ":on-set"))
        (when-some [oc (:on-clear col)]
          (check-invoke (:field col) (:action oc) (:param oc) ":on-clear"))
        (when (and (contains? col :label)
                   (not (and (string? (:label col))
                             (not (str/blank? (:label col))))))
          (err (str (name (:field col)) ": :label is a non-blank string"))))
      ;; the import maps a header back by label or field name, so a
      ;; label names exactly one column: never a reserved header,
      ;; another column's label, or another column's field name
      (doseq [[i col] (map-indexed vector (:columns ws))
              :let [l (:label col)]
              :when l]
        (when (contains? #{"id" "version" "state"} l)
          (err (str (name (:field col)) ": :label " (pr-str l)
                    " is a reserved header")))
        (when (some (fn [[j c]]
                      (and (not= i j)
                           (or (= l (:label c)) (= l (name (:field c))))))
                    (map-indexed vector (:columns ws)))
          (err (str (name (:field col)) ": :label " (pr-str l)
                    " names another column too")))))))
