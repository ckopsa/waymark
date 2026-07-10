(ns waymark10.summary
  "The one-line orientation: a server-rendered template over
  {id}/{kind}/{state}/{version}/{data.field}. Budget ≤140 chars —
  conformance enforces it; this namespace just renders honestly.
  Missing fields render as an em-dash, never crash."
  (:require [clojure.string :as str]))

(defn state-label
  "A state token as prose: :awaiting_payment → \"Awaiting payment\"."
  [state]
  (let [s (str/replace (name state) "_" " ")]
    (str (str/upper-case (subs s 0 1)) (subs s 1))))

(def ^:private token #"\{([A-Za-z0-9_.]+)\}")

(defn- lookup [row path]
  (let [segs (map keyword (str/split path #"\."))]
    (case (first segs)
      :id (:id row)
      :kind (some-> (:kind row) name)
      :version (:version row)
      :state (some-> (:state row) state-label)
      :data (get-in (:data row) (rest segs))
      nil)))

(defn render
  [template row]
  (str/replace template token
               (fn [[_ path]]
                 (let [v (lookup row path)]
                   (if (some? v) (str v) "—")))))
