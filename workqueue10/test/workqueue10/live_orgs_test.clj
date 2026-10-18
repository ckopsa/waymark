(ns workqueue10.live-orgs-test
  "WAYMARK10_ORGS_DIR (tickets 29f8e07a, 77ad4315): an orgs dir holding
  four orgs serves and seeds the two live ones (alpha, gamma) in a
  domain each, with their seats; leaves beta, not live, alone; and
  skips omega, whose kind name collides with alpha's, with a crack,
  while the engine boots. None is a deps root. No database: the
  in-memory twin.

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
  "What WAYMARK10_ORGS_DIR would say: alpha, gamma and omega are live,
  beta is not."
  "fixtures/orgs")

(defn- boot
  "An engine over `storage` as `start!` builds one with FACTORY10_ONLY=1
  and the live orgs `live`, the admitted orgs' seeds' answers, and the
  cracks of the orgs skipped."
  [storage live]
  (let [{:keys [resources orgs cracks]}
        (main/with-live-orgs (main/factory-only-resources) live)
        eng (engine/engine {:storage storage
                            :resources resources
                            :suppress-mirror-refresh true})]
    [eng (main/live-orgs-on-boot! eng orgs {:params {}}) cracks]))

(defn- names [eng kind]
  (set (keep #(get-in % [:data :name]) (dev/rows eng kind))))

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
        [eng once cracks] (boot st live)]
    (is (= ["alpha" "gamma" "omega"] (mapv :org live)))
    (testing "the live orgs' kinds are served, the not-live one's is not"
      (let [kinds (discovered-kinds eng)]
        (is (contains? kinds "sprocket") (pr-str (sort kinds)))
        (is (contains? kinds "gear") (pr-str (sort kinds)))
        (is (not (contains? kinds "cog")))))
    (testing "the colliding org is skipped with a crack naming it, and the engine boots"
      (is (= ["omega"] (mapv :org cracks)))
      (is (= :org_refused (:crack (first cracks))))
      (is (re-find #"sprocket" (str (:reason (first cracks)))) (pr-str cracks))
      (is (not (contains? (names eng :domain) "omega"))))
    (testing "each admitted org's seed ran, into its own domain, with its seat"
      (is (= [true true] (mapv :seeded once)))
      (is (every? (names eng :domain) ["alpha" "gamma"]))
      (is (every? (names eng :seat) ["alpha-mayor" "gamma-mayor"]))
      (is (= 1 (count (dev/rows eng :sprocket))))
      (is (= 1 (count (dev/rows eng :gear)))))
    (testing "a second boot adds nothing"
      (let [[eng2 twice] (boot st live)]
        (is (= [false false] (mapv :seeded twice)))
        (is (every? #(zero? (get-in % [:added :steps] 0)) twice) (pr-str twice))
        (is (= 1 (count (dev/rows eng2 :sprocket))) "no second row")
        (is (= 1 (count (dev/rows eng2 :gear))) "no second row")
        (is (= 2 (count (filter #{"alpha-mayor" "gamma-mayor"}
                                (keep #(get-in % [:data :name]) (dev/rows eng2 :seat))))))))))
