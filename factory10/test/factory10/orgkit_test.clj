(ns factory10.orgkit-test
  "The org kit (docs/orgkit.md): the template and the example org each
  compile against the kit, every kind passes the defresource gate and
  assembles into a registry beside the kinds the engine enrols, and
  each org's seed reads, names only seat templates factorykit ships,
  and holds whole :seat maps the seat kind's create door takes. No
  database, no network."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [waymark10.check :as check]
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.seats :as seats]))

(def ^:private orgs ["myorg" "example"])

(defn- org-resources [org]
  ((requiring-resolve (symbol (str org ".org") "resources"))))

(defn- seed [org]
  (edn/read-string (slurp (io/resource (str org "/seed.edn")))))

(deftest every-org-compiles-with-the-kit
  (doseq [org orgs
          :let [rs (org-resources org)]]
    (testing org
      (is (seq rs))
      (is (every? check/declaration? rs))
      (testing "org name = namespace root = domain name"
        (is (every? #(= (keyword org) (:domain %)) rs)))
      (is (map? (engine/full-registry rs))))))

(deftest every-seed-reads-and-names-the-kit's-templates
  (doseq [org orgs
          :let [s (seed org)
                kinds (set (map (comp name :kind) (org-resources org)))]]
    (testing org
      (is (= org (:org s)))
      (is (= [org] (mapv :name (:domains s))))
      (doseq [{:keys [template]} (:seats s)
              :when template]
        (is (io/resource (str "seats/" template ".edn")) template))
      (doseq [{:keys [template]} (:judgments s)
              :when template]
        (is (io/resource (str "judgments/" template ".edn")) template))
      (doseq [{:keys [kind]} (:steps s)]
        (is (contains? kinds (name kind)) (str kind))))))

(defn- seat-create-errors
  "What the seat kind's create door says of `seat`, as the engine's
  create judges a body: decoded, defaulted, then judged closed; nil
  when the door takes it."
  [seat]
  (let [model (or (:create-schema seats/seat) (:schema seats/seat))]
    (schema/closed-errors model (schema/apply-defaults model (schema/decode model seat)))))

(deftest every-seed's-whole-seat-is-one-the-seat-door-takes
  (doseq [org orgs
          :let [kinds (set (map (comp name :kind) (org-resources org)))]
          {:keys [seat]} (:seats (seed org))
          :when seat]
    (testing (str org " " (:name seat))
      (is (nil? (seat-create-errors seat)))
      (when-some [walk (:walk seat)]
        (is (contains? kinds walk) (str "it walks " walk ", a kind of the org"))))))
