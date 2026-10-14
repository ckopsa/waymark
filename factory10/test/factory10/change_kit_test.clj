(ns factory10.change-kit-test
  "factory10's change kind is composed through `factorykit.change/build`
  (ticket ce3dd7b1), and the composition changed nothing: the
  declaration the engine serves is the one it served when the kind was
  one literal map. The pin below is the digest of that literal's
  declaration, taken on the commit before the kit; a change to the kind
  that is meant moves the pin with it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [factory10.resources.change :refer [change]])
  (:import (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(defn- canonical
  "The declaration as plain data with one printed spelling: a map and a
  set in a sorted order, and a function as the name of its class, with
  the compiler's counter cut off."
  [x]
  (cond
    (map? x) (into [:map] (sort-by pr-str (map (fn [[k v]] [(canonical k) (canonical v)]) x)))
    (set? x) (into [:set] (sort-by pr-str (map canonical x)))
    (sequential? x) (into [:seq] (map canonical x))
    (or (nil? x) (string? x) (keyword? x) (symbol? x) (number? x)
        (boolean? x) (char? x)) x
    (instance? java.util.regex.Pattern x) [:re (str x)]
    (var? x) [:var (str x)]
    :else [:object (str/replace (.getName (class x)) #"(__|eval)\d+" "$1")]))

(defn- digest [x]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical x)) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(def ^:private the-literal-declaration
  "f460daaeb372358cfe0db2f781722fa6fa24f011bb78bc7a3d3ae55f77cb84ee")

(deftest the-composed-change-is-the-declaration-it-was
  (is (= the-literal-declaration (digest change))))
