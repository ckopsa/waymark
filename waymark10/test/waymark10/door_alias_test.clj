(ns waymark10.door-alias-test
  "An old door name still answers for one release, through a logged
  alias: `:aliases {old new}` runs the new door, judged as the new
  door, and the log names `via_alias`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.scheduled :as sched]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]))

(def dsn
  (or (System/getenv "WAYMARK10_TEST_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_test?user=ckopsa"))

(def lantern
  (r/resource
   {:kind :lantern
    :states [:lit :dim :off]
    :initial :lit
    :terminal #{:off}
    :summary "{data.label} · {state}"
    :schema [:map [:label [:string {:max 80}]]]
    :aliases {:fade :dim}
    :actions
    {:dim {:from #{:lit} :to :dim
           :safety {:idempotent true :reversible true :confirm false}}
     :brighten {:from #{:dim} :to :lit
                :safety {:idempotent true :reversible true :confirm false}}
     :switch_off {:from #{:lit :dim} :to :off
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "An off lantern is history."}}}}))

(def ^:dynamic *eng* nil)

(defn- with-engine [f]
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["lanterns" "waymark10_transitions" "waymark10_idempotency"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (binding [*eng* (inv/engine {:storage st :resources [lantern]})]
        (f))
      (finally (pg/close! st)))))

(use-fixtures :once with-engine)

(def opts {:principal (t/principal {:id "colton" :display "Colton"})})

(defn- latest [id]
  (store/with-tx (:storage *eng*)
    (fn [tx]
      (first (store/transitions (:storage *eng*) tx
                                {:kind :lantern :resource-id id}
                                {:newest-first true :limit 1})))))

(defn- via-alias [rec]
  (let [a (:actor rec)]
    (or (get a :via_alias) (get a "via_alias"))))

(defn- lantern! []
  (:id (:row (inv/create! *eng* :lantern {:label "Porch"} opts))))

(deftest load-refuses-a-bad-alias
  (testing "an alias whose new door is not declared"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/resource (assoc lantern :kind :lantern2 :aliases {:fade :nope})))))
  (testing "an alias that shadows a declared door"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/resource (assoc lantern :kind :lantern3 :aliases {:dim :brighten}))))))

(deftest the-old-name-runs-the-new-door
  (let [id (lantern!)]
    (testing "invoking the old name lands where the new door goes, and the log says so"
      (is (= :dim (:state (:row (inv/invoke! *eng* :lantern id :fade nil opts)))))
      (let [rec (latest id)]
        (is (= :dim (:action rec)))
        (is (= "fade" (via-alias rec)))))
    (testing "the new name logs no alias"
      (inv/invoke! *eng* :lantern id :brighten nil opts)
      (is (nil? (via-alias (latest id)))))
    (testing "the new door's machine judges it: fade from off is refused as dim is"
      (inv/invoke! *eng* :lantern id :switch_off nil opts)
      (is (thrown? clojure.lang.ExceptionInfo
                   (inv/invoke! *eng* :lantern id :fade nil opts))))))

(deftest a-stored-call-with-the-old-name-runs
  (let [id (lantern!)]
    (testing "a scheduled target naming the old door is judged by the new door's grant"
      (let [res (#'sched/door-of {:kind "lantern" :action "fade" :id id}
                                 {:rdef-of (constantly lantern)
                                  :grant {:action? (fn [_ a] (= :dim a))}})]
        (is (nil? (:problem res)))
        (is (= :dim (:name (:defn res))))))
    (testing "a stored call with the old name runs, through invoke!"
      (#'sched/attempt *eng* {:kind :lantern :action :fade :id id} {} opts)
      (is (= "fade" (via-alias (latest id))))
      (is (= :dim (:to-state (latest id)))))))
