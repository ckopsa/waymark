(ns waymark10.server.measure
  "GET /api/dashboard_slots/{id}/-/measure: one slot's :measure read
  as a number over a time window (dashboard measures 1/3). The rows
  are the target collection's own — the slot's :where parsed by the
  collection grammar, the reader's grant narrowing exactly as it
  narrows the collection envelope and the worksheet export — kept to
  those whose :at falls in the current or the previous window. The
  answer is {:value :previous :buckets [{:start :value}]}: the stat
  over the current window, over the one before it, and over each of
  the current window's equal buckets, oldest first.

  Recorded choices: count counts rows; sum, median and p90 skip a row
  whose field is empty (or, for a duration, either end); an empty
  window's sum is 0 and its median or p90 null. p90 is nearest-rank.
  A value field the grant does not admit plain answers the collection
  oracle's own 422. Over read-cap rows in the two windows refuses 422,
  the worksheet's honesty — never a silently truncated number."
  (:require [waymark10.dashboard :as dash]
            [waymark10.saved-view :as sv]
            [waymark10.server.collections :as collections]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def ^:private read-cap 10000)

(defn- instant-of ^Instant [v]
  (cond
    (instance? Instant v) v
    (string? v) (try (Instant/parse v) (catch Exception _ nil))
    :else nil))

(defn- value-fn
  "Row → the number the stat reads, or nil to skip the row."
  [parsed]
  (cond
    (:duration parsed)
    (let [[a b] (:duration parsed)]
      (fn [row]
        (let [from (instant-of (get-in row [:data a]))
              to (instant-of (get-in row [:data b]))]
          (when (and from to)
            (/ (double (.toMillis (Duration/between from to))) 1000.0)))))

    (:field parsed)
    (fn [row]
      (let [v (get-in row [:data (:field parsed)])]
        (when (number? v) (double v))))

    :else (constantly 1.0)))

(defn stat-of
  "One stat over one window's values (nil = a row with nothing to read)."
  [stat vs]
  (let [sorted (fn [] (vec (sort (remove nil? vs))))]
    (case stat
      "count" (count vs)
      "sum" (reduce + 0.0 (remove nil? vs))
      "median" (let [s (sorted) n (count s) h (quot n 2)]
                 (when (pos? n)
                   (if (odd? n)
                     (nth s h)
                     (/ (+ (double (nth s (dec h))) (double (nth s h))) 2.0))))
      "p90" (let [s (sorted) n (count s)]
              (when (pos? n)
                (nth s (max 0 (dec (long (Math/ceil (* 0.9 n)))))))))))

(defn- target-rdef [eng target]
  (let [t (str target)]
    (some (fn [[k r]] (when (or (= t (name k)) (= t (:plural r))) r))
          (inv/resources eng))))

(defn report
  "The measure document for one dashboard_slot row, read under vis —
  the per-request grant projection; nil for an unscoped caller."
  [eng slot vis]
  (let [data (:data slot)
        m (or (:measure data)
              (throw (p/problem :not-found 404 "Not found"
                                {:detail "This panel has no measure."})))
        trdef (or (target-rdef eng (:target data))
                  (throw (p/not-found "collection" (str (:target data)))))
        _ (when (and vis (not ((:kind? vis) (:kind trdef))))
            (throw (p/not-found "collection" (:plural trdef))))
        kind (:kind trdef)
        stat (str (:stat m))
        at (keyword (str (:at m)))
        parsed (dash/parse-measure-field (:field m))
        window (long (:window_seconds m))
        n (long (:buckets m))
        now ^Instant ((:now-fn eng))
        from (.minusSeconds now window)
        before (.minusSeconds from window)
        {:keys [conds]} (collections/parse-query
                         trdef
                         (into {} (map (fn [[k v]] [(name k) v]))
                               (sv/parse-where (:where data))))
        read-fields (distinct (cons at (or (:duration parsed)
                                           (some-> (:field parsed) vector))))
        ;; the oracle judges the slot's filters AND every field the
        ;; number reads: a hidden field is not summed into a number
        _ (grants/check-query! vis trdef
                               (concat (remove :default? conds)
                                       (map (fn [f] {:target :data :field f})
                                            read-fields))
                               nil)
        conds (into (vec conds)
                    [{:target :data :field at :cast "timestamptz"
                      :op :>= :value (str before)}
                     {:target :data :field at :cast "timestamptz"
                      :op :< :value (str now)}])
        conds (if-some [ids (when vis ((:ids-of vis) kind))]
                (conj conds {:target :id :op :in :values (vec ids)})
                conds)
        conds (if-some [fconds (when (and vis (:conds-of vis))
                                 ((:conds-of vis) kind))]
                (into conds fconds)
                conds)
        st (:storage eng)
        rows (store/with-tx st
               (fn [tx]
                 (store/search-rows st tx kind conds
                                    {:order-by nil :desc nil
                                     :limit (inc read-cap)})))
        _ (when (> (count rows) read-cap)
            (throw (p/schema-invalid
                    :measure
                    {:rows [(str "over " read-cap
                                 " rows fall in the window — narrow the where")]})))
        value-of (value-fn parsed)
        points (keep (fn [row]
                       (when-some [t (instant-of (get-in row [:data at]))]
                         [t (value-of row)]))
                     rows)
        cur (filterv (fn [[^Instant t _]] (not (.isBefore t from))) points)
        prev (filterv (fn [[^Instant t _]] (.isBefore t from)) points)
        wms (* 1000 window)
        from-ms (.toEpochMilli from)
        by-bucket (group-by (fn [[^Instant t _]]
                              (min (dec n)
                                   (quot (* n (- (.toEpochMilli t) from-ms)) wms)))
                            cur)]
    {:stat stat
     :window {:from (str from) :to (str now) :seconds window}
     :value (stat-of stat (map second cur))
     :previous (stat-of stat (map second prev))
     :buckets (mapv (fn [i]
                      {:start (str (Instant/ofEpochMilli
                                    (+ from-ms (quot (* i wms) n))))
                       :value (stat-of stat (map second (get by-bucket i)))})
                    (range n))}))
