(ns waymark10.consumers-test
  "The live consumer's loop coalesces queued wakes (events/take-wake):
  a burst of transitions is drained in as few log passes as the queue
  allows, and the coalescing loses nothing and repeats nothing — every
  committed transition is heard exactly once, in id order. Real
  Postgres (WAYMARK10_TEST_DSN)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private note
  (r/resource
   {:kind :c_note
    :plural "c_notes"
    :states [:open :filed]
    :initial :open
    :terminal #{:filed}
    :summary "{data.text} · {state}"
    :schema [:map [:text [:string {:min 1 :max 80}]]]
    :actions {:file {:from #{:open} :to :filed
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Filed is filed."}}}}))

(def ^:dynamic *eng* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["c_notes" "members" "roles" "grants" "attachments"
                           "subscriptions" "jobs" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_cursors"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*eng* (engine/engine {:storage st :resources [note]})]
          (f))
        (finally (pg/close! st))))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- note! [text]
  (:row (inv/create! *eng* :c_note {:text text} {:principal elena})))

(defn- await-pred [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

(deftest a-burst-is-heard-once-each-through-coalesced-wakes
  (let [d (events/dispatcher *eng* {:poll-ms 200})
        seen (atom [])
        c (consumers/register-consumer! *eng* :c-burst
                                        #(swap! seen conj (:id %))
                                        {:dispatcher d :poll-ms 200})
        burst 40]
    (try
      (let [notes (mapv #(note! (str "burst " %)) (range burst))]
        (doseq [n notes]
          (inv/invoke! *eng* :c_note (:id n) :file nil {:principal elena}))
        (testing "every transition of the burst reaches the consumer"
          (is (await-pred #(and (>= (count @seen) (* 2 burst))
                                (>= @(:processed c) (* 2 burst)))
                          15000)
              "the coalesced wakes drained the whole burst"))
        (testing "coalescing repeats nothing and keeps id order"
          (is (= (count @seen) (count (distinct @seen))))
          (is (= @seen (sort @seen)))
          (is (= (* 2 burst) @(:processed c)))))
      (finally
        (consumers/stop-consumer! c)
        (events/stop! d)))
    (testing "a drain after the loop stops finds nothing left behind"
      (is (= 0 (consumers/drain-consumer! *eng* :c-burst
                                          #(swap! seen conj (:id %))))))))
