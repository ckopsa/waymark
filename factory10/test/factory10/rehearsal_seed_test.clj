(ns factory10.rehearsal-seed-test
  "An org's outside world is its rehearsal's (ticket a4149376): example's
  seed, whose :rehearsal holds a customer and the world seat, boots on a
  clone with both and boots live without either, with no refusal; and
  off a clone a seed's own acts_as_cast seat is skipped when the org
  says so. No database.

  Run: cd factory10 && clojure -M:test --focus factory10.rehearsal-seed-test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [waymark10.server.domains :as domains]
            [waymark10.server.engine :as engine]
            [waymark10.server.org :as org-boot]
            [waymark10.server.roles :as roles]
            [waymark10.server.seed :as seed-load]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]))

(defn- org-engine
  "A memory engine as the house boots one, the factory's kinds and
  example's, a clone or not."
  [clone?]
  (let [eng (engine/engine {:storage (memory/storage)
                            :clone? clone?
                            :resources (into (vec (main/resources))
                                             ((requiring-resolve 'example.org/resources)))
                            :suppress-mirror-refresh true})]
    (roles/ensure-factory-roles! eng)
    (domains/ensure-factory! eng)
    eng))

(defn- example-seed []
  (edn/read-string (slurp (io/resource "example/seed.edn"))))

(defn- load-row [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(deftest a-rehearsal-loads-on-a-clone-and-nowhere-else
  (let [live-eng (org-engine false)
        live (seed-load/load! live-eng (org-boot/loader-seed (example-seed)) {})
        clone-eng (org-engine true)
        clone (seed-load/load! clone-eng
                               (org-boot/loader-seed (example-seed) {:clone? true})
                               {})]
    (testing "live, the seed loads whole, with no customer and no world seat"
      (is (true? (:seeded live)))
      (is (nil? (load-row live-eng :member "ana"))))
    (testing "on a clone, the customer and the world seat join it"
      (is (true? (:seeded clone)))
      (is (some? (load-row clone-eng :member "ana")))
      (is (= (inc (:members live)) (:members clone)))
      (is (= (inc (:steps live)) (:steps clone))))))

(deftest off-a-clone-a-cast-seat-is-skipped-when-the-org-says-so
  (let [s (example-seed)
        seated (-> s
                   (update :cast merge (get-in s [:rehearsal :cast]))
                   (update :seats into (get-in s [:rehearsal :seats]))
                   (dissoc :rehearsal))]
    (testing "by default the world seat's acts_as_cast is refused"
      (is (thrown? Exception
                   (seed-load/load! (org-engine false) (org-boot/loader-seed seated) {}))))
    (testing "with :rehearsal-seats :skip-off-clone it is left out"
      (is (true? (:seeded (seed-load/load! (org-engine false)
                                           (org-boot/loader-seed seated {:skip-cast-seats? true})
                                           {})))))))
