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
            [waymark10.server.store :as store]
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

(defn- refused-org
  "An org on disk, `delta`, whose seed the loader refuses: its one
  domain names no :name."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "orgs-" (random-uuid)) "delta")
        seed (io/file dir "resources" "delta" "seed.edn")]
    (io/make-parents seed)
    (spit seed (pr-str {:seed "delta" :version 1 :org "delta"
                        :domains [{:label "Delta"}]
                        :cast {:owner {:id "owner" :display "Owner" :type :human}}}))
    {:org "delta" :dir dir}))

(deftest one-org's-seed-refusal-is-a-crack-not-a-crashed-boot
  (let [alpha (filterv #(= "alpha" (:org %)) (org/live-orgs (org/orgs-dir fixtures)))
        {:keys [resources orgs]} (main/with-live-orgs (main/factory-only-resources) alpha)
        eng (engine/engine {:storage (memory/storage)
                            :resources resources
                            :suppress-mirror-refresh true})
        once (atom nil)
        out (with-out-str
              (reset! once (main/live-orgs-on-boot! eng (into [(refused-org)] orgs)
                                                    {:params {}})))
        cracks (filter :crack @once)]
    (testing "the boot goes on: the clean org after it loads"
      (is (= [true] (mapv :seeded (remove :crack @once))) (pr-str @once))
      (is (contains? (names eng :domain) "alpha")))
    (testing "the refused org is skipped with a crack naming it and its sentence"
      (is (= ["delta"] (mapv :org cracks)))
      (is (= :org_refused (:crack (first cracks))))
      (is (re-find #"names no :name" (str (:reason (first cracks)))) (pr-str cracks)))
    (testing "the skip is logged with the org's name"
      (is (re-find #"`delta` did not load at boot" out) out))
    (testing "and a draft ticket says so"
      (is (some #(re-find #"^org delta did not load at boot: " (str (get-in % [:data :title])))
                (dev/rows eng :ticket))
          (pr-str (map #(get-in % [:data :title]) (dev/rows eng :ticket)))))))

(deftest WAYMARK10_ORG-boots-an-org-only-on-disk
  (testing "beta is no deps root, and not live"
    (is (nil? (io/resource "beta/org.clj")))
    (is (false? (org/live? (io/file fixtures "beta")))))
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources (main/with-org (main/factory-only-resources) "beta" fixtures)
                            :suppress-mirror-refresh true})
        result (main/org-on-boot! eng "beta" {:params {} :orgs-dir fixtures})]
    (testing "its kinds are served"
      (is (contains? (discovered-kinds eng) "cog")))
    (testing "its seed loads"
      (is (true? (:seeded result)) (pr-str result))
      (is (contains? (names eng :domain) "beta"))
      (is (= 1 (count (dev/rows eng :cog))))))
  (testing "an org neither place holds says both"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"not on the classpath, nor in `fixtures/orgs/nowhere`"
                          (main/with-org [] "nowhere" fixtures)))))

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

(defn- promoting-org
  "An orgs dir holding one live org, `zeta`, whose one kind promotes a
  field (:release, filterable), as vanilla_plus's contents promotes
  f_release. → the orgs dir."
  []
  (let [root (io/file (System/getProperty "java.io.tmpdir")
                      (str "orgs-" (random-uuid)))
        dir (io/file root "zeta")
        src (io/file dir "src" "zeta" "org.clj")]
    (io/make-parents src)
    (spit (io/file dir "org.edn") (pr-str {:name "zeta" :live true}))
    (spit src (str "(ns zeta.org (:require [waymark10.dsl :refer [defresource in-domain]]))\n"
                   "(defresource gizmo\n"
                   "  {:kind :gizmo :plural \"gizmos\" :states [:new :done] :initial :new\n"
                   "   :terminal #{:done} :summary \"{data.title} · {state}\"\n"
                   "   :schema [:map [:title [:string {:min 1 :max 80}]]\n"
                   "                 [:release {:optional true} :string]]\n"
                   "   :filterable {:state #{:eq :in} :release #{:eq}}\n"
                   "   :actions {:finish {:from #{:new} :to :done\n"
                   "                      :safety {:idempotent true :reversible false :confirm false\n"
                   "                               :one-way \"A finished gizmo stays finished.\"}}}})\n"
                   "(defn resources [] (in-domain :zeta [gizmo]))\n"))
    root))

(deftest the-migrate-plan-reads-the-live-orgs
  ;; ticket b56bbf8e: migrate! planned without the orgs, answered an
  ;; empty plan, and the boot it gated refused on two pending steps
  (let [root (promoting-org)
        {:keys [orgs cracks]} (main/declared (main/factory-only-resources) nil (str root))
        gizmo (some #(when (= :gizmo (:kind %)) %)
                    (main/planned-kinds (main/factory-only-resources) nil (str root)))
        cols (set (map (comp name :name) (:columns (store/kind-projection gizmo))))]
    (testing "the live org is admitted, as start! admits it"
      (is (= ["zeta"] (mapv :org orgs)))
      (is (empty? cracks) (pr-str cracks)))
    (testing "the org kind's promoted column is in what the plan reads"
      (is (some? gizmo))
      (is (contains? cols "f_release") (pr-str (sort cols))))))
