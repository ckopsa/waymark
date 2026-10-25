(ns factorykit.hire-test
  "Hiring a seat (ticket 3d7897a6, M4a-3): on a copy of orgs/example
  with its crew lead let go, a scheduled work order is a crack; the seat
  factorykit.hire drafts for that gap closes it and earns no new
  finding, a second hire takes the next name, and a gap whose doors the
  owner does not hold is refused. No database, no network."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factorykit.conform :as conform]
            [factorykit.hire :as hire])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- seed-file [dir]
  (io/file dir "resources" "example" "seed.edn"))

(defn- example-copy
  "A copy of orgs/example, its seed edited by f. → its dir."
  [f]
  (let [src (io/file "../orgs/example")
        dir (io/file (str (Files/createTempDirectory "hire" (make-array FileAttribute 0)))
                     "example")]
    (doseq [^File file (file-seq src)
            :when (.isFile file)
            :let [to (io/file dir (str (.relativize (.toPath src) (.toPath file))))]]
      (io/make-parents to)
      (io/copy file to))
    (spit (seed-file dir) (pr-str (f (edn/read-string (slurp (seed-file dir))))))
    (str dir)))

(defn- without-crew-lead [seed]
  (update seed :seats (partial filterv #(not= "crew-lead" (get-in % [:seat :name])))))

(def ^:private gap
  {:org "example" :kind "work_order" :state "scheduled"
   :reason "No seat leaves a scheduled work order."
   :key "crack/no_exit/work_order/scheduled"})

(defn- hire!
  "Draft the seat for `gap` and write it into the org's seed. → the step."
  [dir gap]
  (let [step (hire/draft-seat dir gap)]
    (when-not (:error step)
      (spit (seed-file dir) (hire/apply-seat (slurp (seed-file dir)) step)))
    step))

(deftest a-hire-closes-the-crack-it-was-drafted-for
  (let [dir (example-copy without-crew-lead)]
    (is (some #{(:key gap)} (map :key (conform/conform dir)))
        "with no holder of its doors, the state is a crack")
    (let [step (hire! dir gap)]
      (is (= "example-work_order-1" (get-in step [:seat :name])))
      (is (= [{:kind "work_order" :actions ["cancel" "complete"] :filter {:state "scheduled"}}
              {:kind "work_order" :actions []}]
             (get-in step [:seat :scope])))
      (is (= [{:kind "work_order" :filter {:state "scheduled"}}]
             (map #(dissoc % :actions) (:wakes step)))
          "its wakes ride beside the seat")
      (is (seq (get-in step [:wakes 0 :actions])))
      (is (not (contains? (:seat step) :wake_on)) "and not inside it")
      (is (= 10M (get-in step [:seat :budget_usd_per_week])))
      (is (<= 1 (count (re-seq #"\. " (get-in step [:seat :charter]))) 3) "a charter of 2-4 sentences")
      (is (= [] (conform/conform dir)) "no crack there, and no new error"))
    (testing "a second hire for the same kind takes the next name"
      (let [step (hire! dir gap)]
        (is (= "example-work_order-2" (get-in step [:seat :name])))
        (is (= [] (conform/conform dir)))))))

(deftest a-gap-whose-doors-the-owner-lacks-is-refused
  (let [dir (example-copy
             (comp #(assoc-in % [:cast :owner :scope]
                              [{:kind "request" :actions ["*"]}
                               {:kind "work_order" :actions ["create"]}])
                   without-crew-lead))]
    (is (= {:error :scope_exceeds_owner} (hire/draft-seat dir gap)))))

(deftest the-budget-fits-the-domains-headroom
  (let [budget (fn [b] #(update % :domains (partial mapv (fn [d] (assoc d :budget_usd_per_week b)))))]
    (is (= {:error :no_headroom} (hire/draft-seat (example-copy (budget 10M)) gap)))
    (is (= 8M (get-in (hire/draft-seat (example-copy (comp (budget 13M) without-crew-lead)) gap)
                      [:seat :budget_usd_per_week])))))

(deftest apply-seat-keeps-the-seed-text
  (let [text (slurp "../orgs/example/resources/example/seed.edn")
        step (hire/draft-seat "../orgs/example" gap)
        edited (hire/apply-seat text step)]
    (is (= (hire/apply-seat (edn/read-string text) step) (edn/read-string edited)))
    (is (= text (str/replace-first edited (str "\n  " (pr-str step)) ""))
        "every byte but the step's is kept")))
