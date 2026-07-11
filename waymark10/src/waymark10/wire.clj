(ns waymark10.wire
  "One namespace owns every byte that crosses a boundary.

  Law forms travel and persist as a lossless JSON tree encoding —
  never an opaque string — so fingerprints diff path-by-path and any
  stored revision reads back into an evaluable form:

      (every (var :days)
        (or (is-set (get (it 0) :meal_id))
            (= (get (it 0) :eating_out) true)))
  ⇢
      [\"every\", [\"var\",\"days\"],
        [\"or\", [\"is-set\", [\"get\",[\"it\",0],\"meal_id\"]],
                 [\"=\", [\"get\",[\"it\",0],\"eating_out\"], true]]]

  Decimals cross as {\"dec\":\"0.02\"} (exact, no floats), date
  literals as {\"date\":\"2026-07-09\"}.

  Canonical bytes (digests, fingerprint hashes) admit only
  nil/boolean/string/long/keyword/map/vector — floats and raw
  BigDecimals are refused, which forces every decimal through the
  wire encoding and keeps hashes byte-stable across processes."
  (:require [clojure.string :as str]
            [jsonista.core :as j]
            [waymark10.expr :as expr])
  (:import (java.math BigDecimal)
           (java.security MessageDigest)))

(set! *warn-on-reflection* true)

;; ── general JSON (the HTTP boundary) ────────────────────────────────

(def mapper
  "The one Jackson mapper: decimals stay exact, keys keywordize."
  (j/object-mapper {:bigdecimals true :decode-key-fn true}))

(defn read-json [s] (j/read-value s mapper))
(defn write-json ^String [v] (j/write-value-as-string v mapper))

;; ── form ⇄ wire tree ────────────────────────────────────────────────

(def ^:private ref-ops '#{data input var})

(defn form->wire
  "Canonical form → JSON-ready tree (vectors/maps/scalars)."
  [form]
  (cond
    (decimal? form) {"dec" (.toPlainString ^BigDecimal form)}

    (seq? form)
    (let [[op & args] form]
      (cond
        (contains? ref-ops op) [(name op) (name (first args))]
        (= op 'now)  ["now"]
        (= op 'it)   ["it" (first args)]
        (= op 'get)  ["get" (form->wire (first args)) (name (second args))]
        (= op 'date) {"date" (first args)}
        :else (into [(name op)] (map form->wire) args)))

    :else form))

(defn- refuse [msg data]
  (throw (ex-info (str "law wire tree refused: " msg) data)))

(defn- decode
  [tree]
  (cond
    (or (nil? tree) (boolean? tree) (string? tree)) tree
    (int? tree) (long tree)   ; Jackson boxes small ints as Integer

    (double? tree)
    (refuse "float in a law tree — decimals cross as {\"dec\": …}" {:tree tree})

    (decimal? tree)   ; a {"dec": …} that a lenient JSON parser pre-decoded
    (expr/normalize tree)

    (map? tree)
    (let [dec-s  (or (get tree "dec") (get tree :dec))
          date-s (or (get tree "date") (get tree :date))]
      (cond
        (and (string? dec-s) (= 1 (count tree)))
        (try (BigDecimal. ^String dec-s)
             (catch NumberFormatException _
               (refuse (str "unreadable decimal " (pr-str dec-s)) {:tree tree})))
        (and (string? date-s) (= 1 (count tree)))
        (list 'date date-s)
        :else (refuse (str "unknown object node " (pr-str tree)) {:tree tree})))

    (sequential? tree)
    (let [[head & args] tree]
      (if-not (string? head)
        (refuse (str "list node without an operator head: " (pr-str tree)) {:tree tree})
        (let [op (symbol head)]
          (cond
            (not (contains? expr/ops op))
            (refuse (str "unknown operator " (pr-str head)) {:tree tree})

            (contains? ref-ops op)
            (if (and (= 1 (count args)) (string? (first args)))
              (list op (keyword (first args)))
              (refuse (str "(" op " …) wire node takes one field-name string")
                      {:tree tree}))

            (= op 'now) (list 'now)

            (= op 'it)
            (if (and (= 1 (count args)) (nat-int? (first args)))
              (list 'it (long (first args)))
              (refuse "(it …) wire node takes one non-negative index" {:tree tree}))

            (= op 'get)
            (if (and (= 2 (count args)) (string? (second args)))
              (list 'get (decode (first args)) (keyword (second args)))
              (refuse "(get …) wire node takes a tree and a field-name string"
                      {:tree tree}))

            :else (cons op (map decode args))))))

    :else (refuse (str "value " (pr-str tree) " is not a law wire node") {:tree tree})))

(defn wire->form
  "JSON tree → canonical form, refusing anything outside the
  vocabulary — a law that cannot be read back cannot be served.
  Returns the normalized form; throws ex-info with :problems on
  refusal."
  [tree]
  (let [form (decode tree)
        ps (expr/problems form)]
    (when (seq ps)
      (refuse (first ps) {:problems ps :tree tree}))
    (expr/normalize form)))

;; ── canonical bytes and digests ─────────────────────────────────────

(def ^:private canonical-mapper
  (j/object-mapper {:order-by-keys true}))

(defn- check-canonical [v path]
  (cond
    (or (nil? v) (boolean? v) (string? v) (int? v)) nil
    (keyword? v) nil
    (map? v) (doseq [[k x] v]
               (when-not (or (string? k) (keyword? k))
                 (throw (ex-info (str "non-string key " (pr-str k) " at " path)
                                 {:path path})))
               (check-canonical x (conj path (if (keyword? k) (name k) k))))
    (sequential? v) (doall (map-indexed #(check-canonical %2 (conj path %1)) v))
    :else (throw (ex-info (str "value " (pr-str v) " ("
                               (some-> v class .getSimpleName)
                               ") at " path " cannot be canonically encoded — "
                               "decimals cross as {\"dec\": …} wire nodes")
                          {:path path :value v}))))

(defn canonical-json
  "Deterministic JSON: sorted keys, keywords as names, longs/strings/
  booleans/nil only. Refuses floats and raw decimals."
  ^String [v]
  (check-canonical v [])
  (j/write-value-as-string v canonical-mapper))

(defn sha256-hex ^String [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256")
                   (.getBytes s java.nio.charset.StandardCharsets/UTF_8))]
    (str/join (map #(format "%02x" %) d))))

(defn dec-nodes
  "A JSON-shaped value with every exact decimal re-spelled as its wire
  node ({\"dec\" \"0.02\"}) — the input-digest boundary's pre-pass
  (batch H: the first :decimal input field met the digest). Raw
  BigDecimals stay refused from canonical bytes everywhere else; this
  walk is how a decimal-carrying body reaches them lawfully."
  [v]
  (cond
    (decimal? v) {"dec" (.toPlainString ^BigDecimal v)}
    (map? v) (into {} (map (fn [[k x]] [k (dec-nodes x)])) v)
    (sequential? v) (mapv dec-nodes v)
    :else v))

(defn digest
  "Canonical digest of a JSON-shaped value (fingerprint hashes,
  input digests)."
  ^String [v]
  (sha256-hex (canonical-json v)))
