(ns workqueue10.org-boot-test
  "WAYMARK10_ORG (docs/orgkit.md, epic cafa6106 M1b): an engine booted
  with the example org beside the factory serves the org's kinds, holds
  its seed's seat and rows, and skips the seed on a second boot over
  the same store; a broken org kind fails the boot with the registry's
  own message. No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.org-boot-test"
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.org :as org]
            [waymark10.server.scheduled :as scheduled]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]
            [workqueue10.main :as main])
  (:import (java.time Instant)))

(def ^:private example (org/org-name "orgs/example"))

(defn- boot
  "An engine over `storage` as `start!` builds one for WAYMARK10_ORG with
  FACTORY10_ONLY=1, and its org seed's answer."
  [storage]
  (let [eng (engine/engine {:storage storage
                            :resources (main/with-org (main/factory-only-resources) example)
                            :suppress-mirror-refresh true})]
    [eng (main/org-on-boot! eng example {:params {}})]))

(defn- discovered-kinds [eng]
  (let [resp ((engine/handler eng)
              {:request-method :get :uri "/api/.well-known/waymark"
               :headers {"x-waymark-principal" "owner"
                         "x-waymark-actor-type" "human"}})
        body (:body resp)]
    (is (= 200 (:status resp)))
    (set (:kinds (wire/read-json (if (string? body) body (slurp body)))))))

(deftest an-org-boot-serves-its-kinds-and-runs-its-seed-once
  (is (= "example" example))
  (is (= "example" (org/org-name "example.org")))
  (let [st (memory/storage)
        [eng once] (boot st)]
    (testing "its kinds are served beside the factory's"
      (let [kinds (discovered-kinds eng)]
        (is (every? kinds ["request" "work_order" "ticket" "seat"])
            (pr-str (sort kinds)))))
    (testing "its seed's seat and rows exist"
      (is (true? (:seeded once)))
      (is (some #(= "example" (get-in % [:data :name])) (dev/rows eng :domain))
          "its :domains are domain rows")
      (is (some #(= "office-clerk" (get-in % [:data :name])) (dev/rows eng :seat)))
      (is (= 2 (count (dev/rows eng :request))))
      (is (= 1 (count (dev/rows eng :work_order)))))
    (testing "a second boot skips the seed"
      (let [[eng2 twice] (boot st)]
        (is (false? (:seeded twice)))
        (is (= 2 (count (dev/rows eng2 :request))) "no second row")))))

(deftest an-org-clone-gets-the-demo-clock
  (let [t0 (Instant/parse "2026-10-01T12:00:00Z")
        built (fn [org-clone?]
                (#'main/clocked-engine
                 nil org-clone? engine/engine
                 {:storage (memory/storage)
                  :resources (main/with-org (main/factory-only-resources) example)
                  :suppress-mirror-refresh true
                  :clone? false
                  :now-fn (constantly t0)}))]
    (is (true? (#'main/org-clone? example "1")))
    (is (false? (#'main/org-clone? nil "1")))
    (is (false? (#'main/org-clone? example nil)))
    (testing "an org clone serves clock_shift, and a P1D shift moves its clock"
      (let [eng (built true)]
        (is (contains? (inv/resources eng) :clock_shift))
        (inv/create! eng :member {:display "owner" :actor_type "human"}
                     {:principal scheduled/engine-actor :id "owner"})
        (inv/create! eng :clock_shift {:by "P1D"}
                     {:principal (t/principal {:id "owner" :display "Owner"})})
        (is (= (.plusSeconds t0 86400) ((:now-fn eng))))))
    (testing "a working engine serves no clock_shift"
      (let [eng (built false)]
        (is (not (contains? (inv/resources eng) :clock_shift)))
        (is (= t0 ((:now-fn eng))))))))

(deftest a-broken-org-kind-fails-the-boot-with-the-registry's-message
  (let [rs (org/resources example)
        ;; the registry holds one law per kind
        broken (conj rs (first rs))
        message (fn [f] (try (f) nil (catch Exception e (ex-message e))))
        registry's (message #(engine/full-registry
                              (into (main/factory-only-resources) broken)))]
    (is (some? registry's))
    (with-redefs [org/resources (constantly broken)]
      (is (= registry's
             (message #(engine/engine
                        {:storage (memory/storage)
                         :resources (main/with-org (main/factory-only-resources) example)})))))))
