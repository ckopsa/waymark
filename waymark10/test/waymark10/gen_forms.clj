(ns waymark10.gen-forms
  "Generators for law forms and evaluation scopes, shared by the
  phase-0 property tests. Generated forms are canonical-spelling
  (de Bruijn (it n) refs) and well-formed by construction; scopes are
  JSON-shaped values plus the post-coercion types (LocalDate,
  Instant) the boundary produces."
  (:require [clojure.test.check.generators :as gen])
  (:import (java.math BigDecimal)
           (java.time Instant LocalDate)))

(def gen-key
  (gen/elements [:a :b :c :date :items :qty :have :meal_id :eating_out]))

(def gen-decimal
  (gen/let [unscaled (gen/choose -100000 100000)
            scale (gen/choose -2 4)]
    (BigDecimal/valueOf (long unscaled) (int scale))))

(def gen-scalar-literal
  (gen/one-of [gen/string-alphanumeric
               gen/boolean
               (gen/return nil)
               gen/small-integer
               gen-decimal]))

(def gen-date-literal
  (gen/let [y (gen/choose 1990 2100)
            m (gen/choose 1 12)
            d (gen/choose 1 28)]
    (list 'date (format "%04d-%02d-%02d" y m d))))

(defn- gen-leaf [depth]
  (gen/one-of
   (cond-> [gen-scalar-literal
            (gen/fmap #(list 'data %) gen-key)
            (gen/fmap #(list 'input %) gen-key)
            (gen/fmap #(list 'var %) gen-key)
            (gen/return (list 'now))
            gen-date-literal]
     (pos? depth)
     (conj (gen/fmap #(list 'it %) (gen/choose 0 (dec depth)))))))

(defn gen-form*
  "Well-formed canonical form; `depth` = enclosing quantifier count."
  [depth size]
  (if (zero? size)
    (gen-leaf depth)
    (let [sub (gen-form* depth (quot size 2))
          subq (gen-form* (inc depth) (quot size 2))]
      (gen/frequency
       [[4 (gen-leaf depth)]
        [3 (gen/let [op (gen/elements '[= not= < <= > >=])
                     a sub, b sub]
             (list op a b))]
        [2 (gen/let [op (gen/elements '[and or])
                     args (gen/vector sub 1 3)]
             (cons op args))]
        [1 (gen/fmap #(list 'not %) sub)]
        [2 (gen/let [op (gen/elements '[+ - * min max])
                     a sub, b sub]
             (list op a b))]
        [1 (gen/fmap #(list 'abs %) sub)]
        [1 (gen/fmap #(list 'days %) sub)]
        [1 (gen/fmap #(list 'date-of %) sub)]
        [1 (gen/fmap #(list 'is-set %) sub)]
        [1 (gen/let [e sub, k gen-key] (list 'get e k))]
        [2 (gen/let [op (gen/elements '[every some])
                     coll sub, pred subq]
             (list op coll pred))]]))))

(def gen-form
  (gen/sized (fn [size] (gen-form* 0 (min size 12)))))

(def gen-local-date
  (gen/let [epoch-day (gen/choose -20000 40000)]
    (LocalDate/ofEpochDay (long epoch-day))))

(def gen-instant
  (gen/let [s (gen/choose 0 4102444800)]
    (Instant/ofEpochSecond (long s))))

(def gen-value
  "JSON-shaped scope values plus post-coercion date/time types."
  (gen/frequency
   [[6 gen-scalar-literal]
    [2 gen-local-date]
    [1 gen-instant]
    [2 (gen/vector (gen/map gen-key gen-scalar-literal {:max-elements 3}) 0 4)]
    [1 (gen/map gen-key gen-scalar-literal {:max-elements 3})]]))

(def gen-scope
  (gen/let [data (gen/map gen-key gen-value {:max-elements 4})
            input (gen/map gen-key gen-value {:max-elements 4})
            vars (gen/map gen-key gen-value {:max-elements 4})
            its (gen/vector gen-value 0 3)
            now gen-instant]
    {:data data :input input :vars vars :its (seq its) :now now}))
