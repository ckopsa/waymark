(ns factory10.orgkit-test
  "The org kit (docs/orgkit.md): the template and the example org each
  compile against the kit, every kind passes the defresource gate and
  assembles into a registry beside the kinds the engine enrols, and
  each org's seed reads, names only seat templates factorykit ships,
  and holds whole :seat maps the seat kind's create door takes; and
  each org's seed loads whole on a memory engine beside the factory's
  kinds, so the create guards judge it too. No database, no network."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [waymark10.check :as check]
            [waymark10.schema :as schema]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.org :as org-boot]
            [waymark10.server.roles :as roles]
            [waymark10.server.seats :as seats]
            [waymark10.server.seed :as seed-load]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]))

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

(defn- org-engine
  "A memory engine as an org boot builds one: the factory's kinds and
  the org's, with the factory's roles and domain."
  [org]
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (into (vec (main/resources)) (org-resources org))
                            :suppress-mirror-refresh true})]
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    eng))

(deftest every-seed-loads-whole-through-the-doors
  ;; the create guards (a walked kind's default filter, say) run only
  ;; on an engine, so the seed is loaded on one, as org/boot! loads it
  (doseq [org orgs]
    (testing org
      (let [out (try (seed-load/load! (org-engine org) (org-boot/loader-seed (seed org)) {})
                     (catch clojure.lang.ExceptionInfo e
                       {:refused (ex-message e) :data (ex-data e)}))]
        (is (true? (:seeded out)) (pr-str out))))))

(defn- rows-of [eng kind]
  (let [st (:storage eng)]
    (store/with-tx st (fn [tx] (store/query-rows st tx kind {} {:all true})))))

(deftest an-org-without-the-factory-seed-gets-a-house-model
  ;; ticket 239d8101: with no factory seed there was no model row, and
  ;; every seat the org's seed made stayed model-less, so none could be sat
  (doseq [org orgs
          [params want] [[{} "house"] [{:model "claude-test-1"} "claude-test-1"]]]
    (testing (str org " " (pr-str params))
      (let [eng (org-engine org)
            out (seed-load/load! eng (org-boot/loader-seed (seed org)) {:params params})
            models (rows-of eng :model)
            id (some-> (first models) :id str)]
        (is (true? (:seeded out)) (pr-str out))
        (is (= [want] (mapv #(get-in % [:data :name]) models)))
        (is (= "active" (some-> (first models) :state name)))
        (doseq [s (rows-of eng :seat)]
          (is (seq (get-in s [:data :held_for])) (get-in s [:data :name])))
        (is (some #(= [id] (mapv str (get-in % [:data :held_for]))) (rows-of eng :seat))
            "a seated seat is held for the house model")))))
