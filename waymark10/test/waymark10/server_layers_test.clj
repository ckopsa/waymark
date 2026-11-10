(ns waymark10.server-layers-test
  "The server core's dependency direction (docs/server-layers.md): six
  namespaces in layers, each requiring only the ones below it. A require
  that reaches UP a layer — in the ns form, by a `requiring-resolve` or
  `require` in a body, or through any other waymark10 namespace — is a
  back-edge, and every back-edge must be named in
  server_layers_allowlist.edn. A named one that is gone must leave the
  list, so the list only shrinks. Needs no database."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io PushbackReader StringReader)))

(def layers
  "Bottom first: a namespace may require the ones before it."
  '[waymark10.server.invoke
    waymark10.server.grants
    waymark10.server.seats
    waymark10.server.held-calls
    waymark10.server.router
    waymark10.server.mcp])

(defn- libspec-names
  "The namespaces one libspec of a :require names: a symbol, a vector
  with its options, or a prefix list."
  [spec]
  (cond
    (symbol? spec) [spec]
    (and (sequential? spec) (symbol? (first spec)))
    (let [[p & more] spec]
      (if (and (seq more) (not (keyword? (first more))))
        (for [child more
              :let [c (if (sequential? child) (first child) child)]
              :when (symbol? c)]
          (symbol (str p "." c)))
        [p]))
    :else []))

(defn- ns-requires [ns-form]
  (set (for [clause (rest ns-form)
             :when (and (sequential? clause) (#{:require :use} (first clause)))
             spec (rest clause)
             n (libspec-names spec)]
         n)))

(def ^:private late-require
  "A require made in a body: `(requiring-resolve 'ns/var)` or
  `(require 'ns)`."
  #"\((?:requiring-resolve|require)\s+'([A-Za-z0-9.\-]+?)(?:/[^\s)]+)?[\s)]")

(defn- late-requires
  "The namespaces the text requires in its bodies, its comment lines
  left out."
  [text]
  (let [code (str/join "\n" (remove #(re-find #"^\s*;" %) (str/split-lines text)))]
    (set (map (comp symbol second) (re-seq late-require code)))))

(defn- read-ns-form [text]
  (binding [*read-eval* false]
    (let [form (read {:eof ::eof :read-cond :allow}
                     (PushbackReader. (StringReader. text)))]
      (when (and (seq? form) (= 'ns (first form)))
        form))))

(defn- waymark10? [sym]
  (str/starts-with? (str sym) "waymark10."))

(defn- source-graph
  "Every waymark10 namespace under the engine's source root, found
  beside invoke.clj on the classpath, to the waymark10 namespaces it
  requires."
  []
  (let [home (io/file (io/resource "waymark10/server/invoke.clj"))
        root (.getParentFile (.getParentFile home))]
    (into {}
          (for [^java.io.File f (file-seq root)
                :when (str/ends-with? (.getName f) ".clj")
                :let [text (slurp f)
                      form (read-ns-form text)]
                :when form]
            [(second form)
             (set (filter waymark10? (into (ns-requires form)
                                           (late-requires text))))]))))

(defn- shortest-paths
  "Each namespace `from` reaches, to the shortest require path to it."
  [graph from]
  (loop [frontier [from] seen {from [from]}]
    (if (empty? frontier)
      seen
      (let [grown (reduce (fn [acc n]
                            (reduce (fn [acc m]
                                      (if (acc m) acc (assoc acc m (conj (acc n) m))))
                                    acc (graph n)))
                          seen frontier)]
        (recur (vec (remove seen (keys grown))) grown)))))

(defn back-edges
  "Every [from to path] where `from` reaches `to` by requires and `to`
  sits above `from` in `layers`; `path` is the shortest such reach."
  [graph layers]
  (let [rank (zipmap layers (range))]
    (for [from layers
          :let [paths (shortest-paths graph from)]
          to layers
          :when (> (rank to) (rank from))
          :let [path (paths to)]
          :when path]
      [from to path])))

(defn- allowlist []
  (edn/read-string (slurp (io/resource "waymark10/server_layers_allowlist.edn"))))

(deftest back-edges-finds-a-reach-up-a-layer
  (let [graph '{a #{x} x #{c} b #{a} c #{b}}]
    (is (= '#{[a c [a x c]] [a b [a x c b]] [b c [b a x c]]}
           (set (back-edges graph '[a b c])))
        "a direct require down is no back-edge; a reach up through x is")))

(deftest server-core-requires-only-down
  (let [graph (source-graph)
        allowed (allowlist)
        found (back-edges graph layers)
        pair (fn [[from to]] [from to])
        found-pairs (set (map pair found))]
    (is (< 10 (count graph)) "the engine's source is on the classpath")
    (is (every? graph layers) "each layer's namespace was read")
    (testing "a back-edge server_layers_allowlist.edn does not name"
      (let [fresh (remove (comp allowed pair) found)]
        (is (empty? fresh)
            (str/join "\n" (for [[from to path] fresh]
                             (str "[" from " " to "] via "
                                  (str/join " -> " path)))))))
    (testing "an allowlisted back-edge that is gone: remove its line"
      (let [gone (remove found-pairs allowed)]
        (is (empty? gone) (str/join "\n" (map pr-str gone)))))))
