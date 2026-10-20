(ns factory10.law-text-test
  "M2f (ticket 59f0879f): the example org's law, as the page a person
  approves, pinned word for word. A change to the renderer or to the
  example's law shows here as a diff a person can read. No database."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [factory10.law-text :refer [law-text]]
            [factorykit.conform]
            [waymark10.declare :refer [defguard hold]]))

(defn- example-dir
  "orgs/example, found from its seed at <org-dir>/resources/example/seed.edn."
  []
  (.getPath (.getParentFile (.getParentFile (.getParentFile
                                             (io/file (io/resource "example/seed.edn")))))))

(deftest the-example-org's-law-reads-as-the-golden-page
  (let [page (law-text (example-dir))]
    (is (= (slurp (io/resource "factory10/law_text_example.md")) page))
    (is (nil? (re-find #"\w(yed|_ined|offed)\b" page)) page)))

(def ^:private prospect
  {:kind :prospect :plural :prospects :states [:new :qualified] :initial :new
   :create-action-names #{"create"}
   :actions {:qualify {:from [:new] :to :qualified}}})

(def ^:private study
  {:kind :study :plural :studies :states [:collecting :drafting :review :signed_off]
   :initial :collecting :create-action-names #{"create"}
   :actions {:records_in {:from [:collecting] :to :drafting}
             :submit {:from [:drafting] :to :review}
             :send_back {:from [:review] :to :drafting}
             :sign_off {:from [:review] :to :signed_off}}})

(deftest a-seat's-wakes-read-as-the-doors'-own-moves
  (let [seat {:name "studier" :cadence_seconds 86400
              :wake_on [{:kind "prospect" :actions ["create" "qualify"]}
                        {:kind "study" :actions ["create" "records_in" "submit"
                                                 "send_back" "sign_off"]}]}
        page (str/join "\n" (#'factory10.law-text/seats-section [prospect study] [seat]))]
    (is (str/includes?
         page
         (str "Wakes every day, and when a prospect is written, a new prospect becomes qualified, "
              "a study is written, a collecting study becomes drafting, a drafting study becomes review, "
              "a review study becomes drafting or a review study becomes signed off."))
        page)
    (is (nil? (re-find #"\w(yed|_ined|offed)\b" page)) page)))

(defguard principal-signs-off
  (hold "A study is signed off only on The Principal's tap."))

(deftest a-hold-clause-reads-as-a-door-a-person-taps
  (let [held (assoc-in study [:actions :sign_off :guards] [principal-signs-off])
        page (str/join "\n" (#'factory10.law-text/held-section [held]))]
    (is (str/includes? page "## Doors a person taps") page)
    (is (str/includes? page "study **sign_off**: A study is signed off only on The Principal's tap.") page)
    (is (#'factorykit.conform/held? (get-in held [:actions :sign_off]))
        "conform's held? sees it")))
