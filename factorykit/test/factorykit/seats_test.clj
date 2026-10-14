(ns factorykit.seats-test
  "The seat templates (ticket 64543127): each one reads, names every
  hole it has, and fills into a map the seat kind's create door takes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private names ["draft-judge" "code-seat" "ops-seat" "sitting-judge"])

(defn- template [n]
  (edn/read-string (slurp (io/resource (str "seats/" n ".edn")))))

(defn- holes [s]
  (set (map (comp keyword second) (re-seq #"\{\{([a-z_]+)\}\}" s))))

(defn- filled
  "The template's seat with every hole its params name filled."
  [{:keys [template seat]}]
  (edn/read-string
   (reduce (fn [s k] (str/replace s (str "{{" (name k) "}}") "owner/repo"))
           (pr-str seat)
           (keys (:params template)))))

(deftest every-template-fills-into-a-seat-the-seat-kind-takes
  (doseq [n names
          :let [t (template n)
                seat (filled t)]]
    (testing n
      (is (= (holes (pr-str (:seat t))) (set (keys (:params (:template t)))))
          "every hole is a param, and every param a hole")
      (is (= n (:name seat)))
      (is (<= 1 (count (:charter seat)) 1200) "the charter's cap")
      (is (<= (count (:instructions seat)) 2000) "the instructions' cap")
      (is (seq (:scope seat)))
      (is (every? #(and (string? (:kind %)) (vector? (:actions %))) (:scope seat)))
      (doseq [k [:standing_ttl_seconds :cadence_seconds
                 :budget_usd_per_week :sitting_budget_tokens]]
        (is (contains? seat k) (str "it states " (name k)))))))
