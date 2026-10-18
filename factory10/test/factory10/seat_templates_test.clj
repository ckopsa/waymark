(ns factory10.seat-templates-test
  "Every factorykit seat template that sits in a domain, filled and
  created through the seat kind's create door against a real domain
  row, reads its computed `in_domain` back as that domain's name
  (ticket f7d7c8a3): the template writes `:domain`, the ref, and not
  `:in_domain`.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [waymark10.dev :as dev]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.invoke :as inv]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-18T08:00:00Z"))

(def ^:private names ["draft-judge" "ops-seat" "sitting-judge" "mayor"])

(def ^:private person {"x-waymark-principal" "colton"})

(def ^:private a-person (t/principal {:id "colton" :type :human}))

(defn- req [h method uri body]
  (h (cond-> {:request-method method :uri uri :headers person}
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(defn- edn-resource [path]
  (edn/read-string (slurp (io/resource path))))

(defn- fill
  "The template's `part` with each hole its params name filled from
  `values`."
  [t part values]
  (edn/read-string
   (reduce (fn [s k] (str/replace s (str "{{" (name k) "}}") (get values k)))
           (pr-str (get t part))
           (keys (get-in t [:template :params])))))

(defn- made! [h plural body]
  (let [made (req h :post (str "/api/" plural) body)]
    (assert (= 201 (:status made)) (pr-str (json made)))
    (id-of made)))

(defn- judgment!
  "The judgment template `jn`, created and promoted. → its id."
  [eng h jn]
  (let [id (made! h "judgments"
                  (fill (edn-resource (str "judgments/" jn ".edn")) :judgment
                        {:ticket_repo "ckopsa/waymark"}))]
    (inv/invoke! eng :judgment id :promote {} {:principal a-person})
    id))

(defn- powers!
  "Each power token the templates' scopes name, registered, so the
  scope names real kinds as it does on the house."
  [eng templates]
  (doseq [token (into (sorted-set)
                      (comp (mapcat #(get-in % [:seat :scope]))
                            (map :kind)
                            (filter #(str/includes? % ".")))
                      templates)]
    (inv/create! eng :capability
                 {:token token
                  :description (str token " through Gate.")
                  :enforced_by "gate-mcp"}
                 {:principal a-person})))

(deftest every-domain-template-creates-a-seat-whose-in-domain-is-its-domain
  (let [eng (dev/scratch! (conj (vec (main/resources)) caps/capability)
                          {:now-fn (constantly t0)})
        h (dev/handler eng)
        domain-id (made! h "domains" {:name "landscaping"
                                      :charter "Run the quotes and the crews."
                                      :budget_usd_per_week 1000})]
    (powers! eng (map #(edn-resource (str "seats/" % ".edn")) names))
    (doseq [n names
            :let [t (edn-resource (str "seats/" n ".edn"))
                  jn (get-in t [:template :judgment_template])
                  ;; short: draft-judge's instructions sit at the cap
                  values {:repos "o/r"
                          :restart_jobs "clone-mcp"
                          :judgment (when jn (judgment! eng h jn))
                          :domain "landscaping"
                          :domain_id domain-id}
                  ;; the mayor's journal is workqueue10's kind, which
                  ;; this engine does not assemble
                  seat (update (fill t :seat values) :scope
                               (partial filterv #(not= "journal" (:kind %))))
                  made (req h :post "/api/seats" seat)]]
      (testing n
        (is (= 201 (:status made)) (pr-str (json made)))
        (let [row (json (req h :get (str "/api/seats/" (id-of made)) nil))]
          (is (= domain-id (get-in row [:data :domain])))
          (is (= "landscaping" (get-in row [:data :in_domain]))))))))

(deftest the-onboarding-seat-reaches-every-door-its-walk-needs-and-nothing-else
  (let [eng (dev/scratch! (conj (vec (main/resources)) caps/capability)
                          {:now-fn (constantly t0)})
        h (dev/handler eng)
        t (edn-resource "seats/onboarding.edn")
        seat (fill t :seat {:org "greenleaf"})
        vocab (into {} (map (fn [[k rd]] [(name k) (inv/action-names rd)]))
                    (inv/resources eng))
        kinds (remove #(str/includes? (:kind %) ".") (:scope seat))
        powers (filter #(str/includes? (:kind %) ".") (:scope seat))]
    (powers! eng [t])
    (testing "it fills and creates through the seat door"
      (let [made (req h :post "/api/seats" seat)]
        (is (= 201 (:status made)) (pr-str (json made)))))
    (testing "its walk and every door its scope names are ones the engine serves"
      (is (contains? vocab (:walk seat)))
      (doseq [{:keys [kind actions]} kinds
              a actions]
        (is (contains? (get vocab kind) a) (str kind "." a))))
    (testing "it holds the walk's doors and no other"
      (is (= {"org_plan" #{"ask" "propose"}
              "change" #{"submit" "stall"}}
             (into {} (map (juxt :kind (comp set :actions))) kinds)))
      (is (= {:state "drafting"}
             (:filter (first (filter #(= "org_plan" (:kind %)) kinds))))
          "the walk is the drafting plans, and propose is its way out"))
    (testing "its bench reaches the org's directory in ckopsa/waymark, and no live power"
      (is (seq powers))
      (doseq [{:keys [kind filter]} powers]
        (is (str/starts-with? kind "bench.") kind)
        (is (= "ckopsa/waymark" (:repo filter)) kind)
        (when (#{"bench.find" "bench.read" "bench.edit"} kind)
          (is (= "orgs/greenleaf/**" (:path filter)) kind))))))
