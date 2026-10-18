(ns workqueue10.live-orgs-test
  "WAYMARK10_ORGS_DIR (ticket 29f8e07a): an orgs dir holding two orgs,
  one whose manifest says live, serves and seeds only that one, and
  neither is a deps root. No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.live-orgs-test"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.engine :as engine]
            [waymark10.server.org :as org]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]
            [workqueue10.main :as main]))

(def ^:private fixtures
  "What WAYMARK10_ORGS_DIR would say: alpha is live, beta is not."
  "fixtures/orgs")

(defn- boot
  "An engine over `storage` as `start!` builds one with FACTORY10_ONLY=1
  and the live orgs `live`, and their seeds' answers."
  [storage live]
  (let [eng (engine/engine {:storage storage
                            :resources (main/with-live-orgs (main/factory-only-resources) live)
                            :suppress-mirror-refresh true})]
    [eng (main/live-orgs-on-boot! eng live {:params {}})]))

(defn- discovered-kinds [eng]
  (let [resp ((engine/handler eng)
              {:request-method :get :uri "/api/.well-known/waymark"
               :headers {"x-waymark-principal" "owner"
                         "x-waymark-actor-type" "human"}})
        body (:body resp)]
    (is (= 200 (:status resp)))
    (set (:kinds (wire/read-json (if (string? body) body (slurp body)))))))

(deftest the-orgs-dir-is-the-variable-or-the-image's-orgs
  (is (= (io/file fixtures) (org/orgs-dir fixtures)))
  (is (= (io/file "../orgs") (org/orgs-dir nil)))
  (is (= (io/file "../orgs") (org/orgs-dir "  "))))

(deftest only-the-live-org-loads-from-the-orgs-dir
  (testing "neither org is a deps root"
    (is (nil? (io/resource "alpha/org.clj")))
    (is (nil? (io/resource "beta/org.clj"))))
  (testing "each manifest says whether its org is live"
    (is (= {:name "alpha" :live true} (org/manifest (io/file fixtures "alpha"))))
    (is (true? (org/live? (io/file fixtures "alpha"))))
    (is (false? (org/live? (io/file fixtures "beta")))))
  (let [live (org/live-orgs (org/orgs-dir fixtures))
        st (memory/storage)
        [eng once] (boot st live)]
    (is (= ["alpha"] (mapv :org live)))
    (testing "the live org's kind is served, the other's is not"
      (let [kinds (discovered-kinds eng)]
        (is (contains? kinds "sprocket") (pr-str (sort kinds)))
        (is (not (contains? kinds "cog")))))
    (testing "its seed ran"
      (is (= [true] (mapv :seeded once)))
      (is (some #(= "alpha" (get-in % [:data :name])) (dev/rows eng :domain)))
      (is (= 1 (count (dev/rows eng :sprocket)))))
    (testing "a second boot skips the seed"
      (let [[eng2 twice] (boot st live)]
        (is (= [false] (mapv :seeded twice)))
        (is (= 1 (count (dev/rows eng2 :sprocket))) "no second row")))))
