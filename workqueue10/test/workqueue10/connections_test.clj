(ns workqueue10.connections-test
  "The breaker panel (waymark-kyg.1): connection rows seed at boot,
  the passes keep their health honest, and the state flips only on
  the run's edges — one blip is not an outage, one answer ends one.
  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [workqueue10.confluence :as conf]
            [workqueue10.connections :as connections
             :refer [connection]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private tables
  ["connections" "members" "roles" "grants" "approval_requests"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (binding [*eng* (engine/engine {:storage st
                                        :resources [connection]})]
          (f))
        (finally (pg/close! st))))))

(defn- row [tag]
  (let [st (:storage *eng*)
        raw (store/with-tx st
              (fn [tx]
                (first (store/query-rows st tx :connection
                                         {:tag tag} {:limit 1}))))]
    (some->> raw (inv/decode-row (get (inv/resources *eng*) :connection)))))

(deftest the-panel-seeds-once-and-keeps-what-the-passes-wrote
  (connections/ensure-connections!
   *eng* {"gtasks" {:provider "google" :mode "real"}
          "hub" {:mode "real"}})
  (let [r (row "gtasks")]
    (is (= :live (:state r)))
    (is (= "google" (get-in r [:data :provider])))
    (is (= "real" (get-in r [:data :mode]))))

  (testing "re-seeding never overwrites — health is the passes' record"
    (connections/report! *eng* "gtasks" false "boom")
    (connections/ensure-connections!
     *eng* {"gtasks" {:provider "google" :mode "real"}})
    (is (= 1 (long (get-in (row "gtasks")
                           [:data :consecutive_failures]))))))

(deftest darkness-has-edges
  (connections/ensure-connections! *eng* {"todo" {:mode "real"}})

  (testing "one failed pass records but does not flip — a blip is
            not an outage"
    (connections/report! *eng* "todo" false "connection refused")
    (let [r (row "todo")]
      (is (= :live (:state r)))
      (is (= "connection refused" (get-in r [:data :last_error])))
      (is (some? (get-in r [:data :failed_since])))))

  (testing "the second consecutive failure flips dark, audited"
    (connections/report! *eng* "todo" false "connection refused")
    (is (= :dark (:state (row "todo"))))
    (let [ts (store/with-tx (:storage *eng*)
               (fn [tx] (store/transitions (:storage *eng*) tx
                                           {:kind :connection
                                            :resource-id (:id (row "todo"))}
                                           {})))]
      (is (some #(= "mark_dark" (name (:action %))) ts))))

  (testing "further failures keep counting on the dark row"
    (connections/report! *eng* "todo" false "still down")
    (let [r (row "todo")]
      (is (= :dark (:state r)))
      (is (= 3 (long (get-in r [:data :consecutive_failures]))))
      (is (= "still down" (get-in r [:data :last_error])))))

  (testing "the first answer flips live and clears the run"
    (connections/report! *eng* "todo" true nil)
    (let [r (row "todo")]
      (is (= :live (:state r)))
      (is (nil? (get-in r [:data :last_error])))
      (is (nil? (get-in r [:data :failed_since])))
      (is (= 0 (long (get-in r [:data :consecutive_failures]))))
      (is (some? (get-in r [:data :last_answered]))))))

(deftest a-tag-the-panel-never-seeded-says-nothing
  (connections/report! *eng* "ghost" false "boom")
  (is (nil? (row "ghost"))))

(deftest a-reconsent-between-a-passs-read-and-write-survives
  ;; waymark-kyg.2: the health pass reads the row, then persist! writes
  ;; the health keys onto a FRESH read — a refresh_token landed by the
  ;; reconsent door in that gap must not be clobbered back to absent.
  (connections/ensure-connections!
   *eng* {"race" {:provider "google" :mode "real"}})
  (let [stale (row "race")]                 ; the pass's own snapshot
    (is (nil? (get-in stale [:data :refresh_token])))
    ;; the reconsent door lands its credential AFTER the pass read it
    (connections/receive-token! *eng* (:id stale)
                                {:refresh-token "rt-FRESH"
                                 :reconsented-by "ana"})
    ;; the pass now writes its health — over the stale snapshot
    (#'connections/persist! *eng* stale
                            {:last_answered (java.time.Instant/now)
                             :last_error nil
                             :failed_since nil
                             :consecutive_failures 0})
    (let [r (row "race")]
      (testing "the credential written in the gap survived the write"
        (is (= "rt-FRESH" (get-in r [:data :refresh_token]))))
      (testing "the health keys the pass owns still landed"
        (is (= 0 (long (get-in r [:data :consecutive_failures]))))
        (is (some? (get-in r [:data :last_answered])))))))

(deftest the-panel-is-the-systems-own-record
  (connections/ensure-connections! *eng* {"chore" {:mode "real"}})
  (testing "a human can read the breaker but never work it"
    (let [ana (t/principal {:id "ana" :type :human :display "Ana"})]
      (is (thrown? Exception
                   (inv/create! *eng* :connection
                                {:tag "sneaky" :mode "real"}
                                {:principal ana})))
      (is (thrown? Exception
                   (inv/invoke! *eng* :connection (:id (row "chore"))
                                :mark_dark {:error "says a person"}
                                {:principal ana}))))))

(deftest the-confluence-fan-reports-per-tag
  (connections/ensure-connections! *eng* {"broken" {:mode "real"}
                                          "fine" {:mode "real"}})
  (let [eng-ref (atom *eng*)
        broken (reify conf/TaskSource
                 (source-discover [_]
                   (throw (ex-info "401 invalid_grant" {})))
                 (source-pull [_ _] nil)
                 (source-pull-many [_ _] {})
                 (source-push [_ _ _] nil)
                 (source-create [_ _] nil))
        fine (conf/fake-source)
        feed (conf/confluence {"broken" broken "fine" fine}
                              (connections/fan-reporter eng-ref))]
    (mirror/discover feed)
    (mirror/discover feed)
    (testing "the broken tag went dark wearing the real error; its
              healthy neighbor stayed live — partial tolerance, told"
      (let [r (row "broken")]
        (is (= :dark (:state r)))
        (is (= "401 invalid_grant" (get-in r [:data :last_error]))))
      (let [r (row "fine")]
        (is (= :live (:state r)))
        (is (some? (get-in r [:data :last_answered])))))))
