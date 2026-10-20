(ns factory10.live-hire-test
  "The house notices it needs help (ticket 29b819dd, M4a): over a memory
  engine with orgs/example loaded and its found plan live, the seat
  that holds a request in `new` is parked and the seed's lawn request
  ages past its day. One live cracks pass opens one hire plan for the
  gap, reason no_holder, and files no draft for the crack; a second
  opens nothing; a retired hire plan lets the next pass open another.
  A row outside a live org still files its draft. No database.

  Run: cd factory10 && clojure -M:test --focus factory10.live-hire-test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live-cracks :as live]
            [waymark10.server.org :as org-boot]
            [waymark10.server.roles :as roles]
            [waymark10.server.seed :as seed-load]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

;; now, so a row the store stamps on the real clock ages on the engine's
(def ^:private t0 (Instant/now))

(def ^:private the-owner
  (t/principal {:id "owner" :type :human :display "Rosa Delgado"}))

(defn- org-engine
  "A memory engine as the house boots one, its clock `clock`, with
  example's seed loaded and its found plan live."
  [clock]
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (into (vec (main/resources))
                                             ((requiring-resolve 'example.org/resources)))
                            :now-fn #(deref clock)
                            :suppress-mirror-refresh true})]
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    (seed-load/load! eng (org-boot/loader-seed
                          (edn/read-string (slurp (io/resource "example/seed.edn"))))
                     {})
    (walk/live! eng (:id (:row (inv/create! eng :org_plan {:name "example"}
                                            {:principal the-owner})))
                the-owner)
    eng))

(defn- rows-of [eng kind]
  (mapv #(inv/decode-row (get (inv/resources eng) kind) %)
        (store/with-tx (:storage eng)
          (fn [tx] (store/query-rows (:storage eng) tx kind {} {:limit 1000})))))

(defn- hires [eng]
  (filterv #(= "hire" (org-plan/purpose-of %)) (rows-of eng :org_plan)))

(defn- standing-hires [eng]
  (filterv #(not= "retired" (name (:state %))) (hires eng)))

(defn- drafts-for
  "The tickets the pass filed for this row's aged crack."
  [eng kind id]
  (filterv #(str/includes? (str (get-in % [:data :detail]))
                           (str "[crack aged:" kind ":" id "]"))
           (rows-of eng :ticket)))

(defn- park-the-clerk!
  "Park the seat that walks requests. → the lawn request's id."
  [eng]
  (let [clerk (some #(when (= "office-clerk" (str (get-in % [:data :name]))) %)
                    (rows-of eng :seat))]
    (inv/invoke! eng :seat (str (:id clerk)) :park nil {:principal the-owner})
    (str (:id (some #(when (= "new" (name (:state %))) %) (rows-of eng :request))))))

(deftest an-aged-request-nobody-holds-opens-one-hire-plan
  (let [clock (atom t0)
        eng (org-engine clock)
        lawn (park-the-clerk! eng)]
    (reset! clock (.plusSeconds t0 (* 2 86400)))
    (live/pass! eng)
    (testing "one pass opens one hire plan, reason no_holder"
      (let [[h :as all] (standing-hires eng)]
        (is (= 1 (count all)) (pr-str all))
        (is (= "drafting" (name (:state h))))
        (is (= "example" (str (get-in h [:data :name]))))
        (is (= "no_holder" (str (get-in h [:data :gap :reason]))))
        (is (= "example/request/new" (str (get-in h [:data :gap :key]))))))
    (testing "and files no draft for the crack"
      (is (empty? (drafts-for eng "request" lawn))))
    (testing "a second pass opens nothing"
      (live/pass! eng)
      (is (= 1 (count (hires eng))))
      (is (empty? (drafts-for eng "request" lawn))))
    (testing "a retired hire plan lets the next pass open another"
      (inv/invoke! eng :org_plan (str (:id (first (hires eng)))) :retire
                   {:why "Not this one."} {:principal the-owner})
      (reset! clock (.plusSeconds t0 (* 3 86400)))
      (live/pass! eng)
      (is (= 2 (count (hires eng))))
      (is (= 1 (count (standing-hires eng)))))))

(deftest a-row-outside-a-live-org-still-files-its-draft
  ;; the found plan retired, example is no live org: its aged request
  ;; is any domain's row, and goes to the mayor as before
  (let [clock (atom t0)
        eng (org-engine clock)
        lawn (park-the-clerk! eng)]
    (inv/invoke! eng :org_plan (str (:id (first (rows-of eng :org_plan)))) :retire
                 {:why "We sold the business."} {:principal the-owner})
    (reset! clock (.plusSeconds t0 (* 2 86400)))
    (live/pass! eng)
    (is (empty? (hires eng)))
    (is (= 1 (count (drafts-for eng "request" lawn))))))
